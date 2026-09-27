package com.ledgerx.wallet.application;

import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.FinancialValidationException;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.transfer.domain.IdempotencyKeyReuseException;
import com.ledgerx.transfer.domain.IdempotencyRequestInProgressException;
import com.ledgerx.transfer.domain.WalletNotFoundException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local-only test funding. The clearing debit and wallet credit commit with the receipt. */
@Service
@Profile({"local", "test"})
public class DemoFundingService {

  private static final String CLEARING_CODE = "LOCAL_DEMO_FUNDING_CLEARING";
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000.00");

  private final LedgerAccountRepository accountRepository;
  private final LedgerPostingService postingService;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public DemoFundingService(
      LedgerAccountRepository accountRepository,
      LedgerPostingService postingService,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.accountRepository = accountRepository;
    this.postingService = postingService;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Transactional
  public FundingExecution add(UUID ownerId, UUID walletId, Money money, String idempotencyKey) {
    if (money.currency() != CurrencyCode.USD
        || !money.isPositive()
        || money.amount().compareTo(MAX_AMOUNT) > 0) {
      throw new FinancialValidationException(
          "demo funding must be between $0.01 and $10,000.00 USD");
    }
    LedgerAccount wallet =
        accountRepository
            .findById(walletId)
            .filter(account -> account.isWallet() && ownerId.equals(account.ownerId()))
            .orElseThrow(() -> new WalletNotFoundException("wallet was not found"));

    UUID fundingId = UUID.randomUUID();
    Instant createdAt = clock.instant();
    int claimed =
        jdbcTemplate.update(
            """
            INSERT INTO ledgerx.demo_fundings
                (id, owner_id, wallet_id, idempotency_key, amount, currency, created_at)
            VALUES (?, ?, ?, ?, ?, 'USD', ?)
            ON CONFLICT (owner_id, idempotency_key) DO NOTHING
            """,
            fundingId,
            ownerId,
            walletId,
            idempotencyKey,
            money.amount(),
            Timestamp.from(createdAt));
    if (claimed == 0) {
      FundingReceipt existing = findByKey(ownerId, idempotencyKey);
      if (!existing.walletId().equals(walletId)
          || existing.amount().compareTo(money.amount()) != 0) {
        throw new IdempotencyKeyReuseException("demo funding key was used for another request");
      }
      if (existing.ledgerTransactionId() == null) {
        throw new IdempotencyRequestInProgressException("demo funding is still processing");
      }
      return new FundingExecution(existing, true);
    }

    UUID clearingId = clearingAccountId();
    UUID ledgerTransactionId =
        postingService.post(
            "Local demo funding " + fundingId,
            List.of(
                new PostingLine(clearingId, EntrySide.DEBIT, money),
                new PostingLine(wallet.id(), EntrySide.CREDIT, money)));
    Instant completedAt = clock.instant();
    jdbcTemplate.update(
        """
        UPDATE ledgerx.demo_fundings
        SET ledger_transaction_id = ?, completed_at = ?
        WHERE id = ?
        """,
        ledgerTransactionId,
        Timestamp.from(completedAt),
        fundingId);
    return new FundingExecution(
        new FundingReceipt(fundingId, walletId, money.amount(), ledgerTransactionId, completedAt),
        false);
  }

  private UUID clearingAccountId() {
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.ledger_accounts
            (id, account_kind, account_type, owner_id, system_code, currency, status, created_at)
        VALUES (?, 'SYSTEM', 'ASSET', NULL, ?, 'USD', 'ACTIVE', ?)
        ON CONFLICT DO NOTHING
        """,
        UUID.randomUUID(),
        CLEARING_CODE,
        Timestamp.from(clock.instant()));
    return jdbcTemplate.queryForObject(
        "SELECT id FROM ledgerx.ledger_accounts WHERE system_code = ?", UUID.class, CLEARING_CODE);
  }

  private FundingReceipt findByKey(UUID ownerId, String idempotencyKey) {
    return jdbcTemplate.queryForObject(
        """
        SELECT id, wallet_id, amount, ledger_transaction_id, completed_at
        FROM ledgerx.demo_fundings
        WHERE owner_id = ? AND idempotency_key = ?
        """,
        (resultSet, rowNumber) ->
            new FundingReceipt(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("wallet_id", UUID.class),
                resultSet.getBigDecimal("amount"),
                resultSet.getObject("ledger_transaction_id", UUID.class),
                resultSet.getTimestamp("completed_at") == null
                    ? null
                    : resultSet.getTimestamp("completed_at").toInstant()),
        ownerId,
        idempotencyKey);
  }

  public record FundingReceipt(
      UUID fundingId,
      UUID walletId,
      BigDecimal amount,
      UUID ledgerTransactionId,
      Instant completedAt) {}

  public record FundingExecution(FundingReceipt receipt, boolean replayed) {}
}
