package com.ledgerx.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.AccountUnavailableException;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.InsufficientFundsException;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerPostingIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private WalletAccountService walletAccountService;
  @Autowired private LedgerPostingService ledgerPostingService;
  @Autowired private LedgerBalanceQueryService ledgerBalanceQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeEach
  void clearFinancialData() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE ledgerx.webhook_secret_reencryptions, ledgerx.outbox_replay_requests, ledgerx.risk_review_actions, ledgerx.risk_review_cases, ledgerx.risk_assessments, ledgerx.demo_fundings, ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
  }

  @Test
  void postsBalancedFundingAndDerivesWalletBalanceFromHistory() {
    WalletRegistration wallet = walletAccountService.createWallet(OwnerType.PERSON);
    UUID clearingAccountId =
        walletAccountService.createSystemAccount(AccountType.ASSET, "CLEARING");

    UUID transactionId =
        ledgerPostingService.post(
            "Initial funding",
            List.of(
                line(clearingAccountId, EntrySide.DEBIT, "100.00"),
                line(wallet.walletAccountId(), EntrySide.CREDIT, "100.00")));

    assertThat(ledgerBalanceQueryService.balanceOf(wallet.walletAccountId()).amount())
        .isEqualByComparingTo("100.00");
    assertThat(ledgerBalanceQueryService.balanceOf(clearingAccountId).amount())
        .isEqualByComparingTo("100.00");
    assertThat(countEntries(transactionId)).isEqualTo(2);
    assertThat(unbalancedTransactionCount()).isZero();
  }

  @Test
  void rejectsASecondWalletForTheSameOwnerAndCurrency() {
    UUID ownerId = walletAccountService.createOwner(OwnerType.MERCHANT);
    walletAccountService.createWalletForOwner(ownerId);

    assertThatThrownBy(() -> walletAccountService.createWalletForOwner(ownerId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void concurrentWalletCreationKeepsOneOwnerCurrencyAccount() throws Exception {
    UUID ownerId = walletAccountService.createOwner(OwnerType.MERCHANT);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first = executor.submit(() -> attemptWalletCreation(ownerId, ready, start));
      Future<Boolean> second = executor.submit(() -> attemptWalletCreation(ownerId, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      executor.shutdownNow();
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.ledger_accounts WHERE owner_id = ? AND currency = 'USD'",
                Integer.class,
                ownerId))
        .isEqualTo(1);
  }

  @Test
  void rejectsPostingToASuspendedWalletWithoutWritingHistory() {
    FundedWallet fundedWallet = fundedWallet("100.00");
    walletAccountService.suspendWallet(fundedWallet.walletAccountId());

    assertThatThrownBy(
            () ->
                ledgerPostingService.post(
                    "Attempt a debit from a suspended wallet",
                    List.of(
                        line(fundedWallet.walletAccountId(), EntrySide.DEBIT, "10.00"),
                        line(fundedWallet.clearingAccountId(), EntrySide.CREDIT, "10.00"))))
        .isInstanceOf(AccountUnavailableException.class);

    assertThat(transactionCount()).isEqualTo(1);
    assertThat(ledgerBalanceQueryService.balanceOf(fundedWallet.walletAccountId()).amount())
        .isEqualByComparingTo("100.00");
  }

  @Test
  void databaseRejectsAndRollsBackAnUnbalancedJournalHeader() {
    UUID transactionId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status ->
                        jdbcTemplate.update(
                            """
                            INSERT INTO ledgerx.ledger_transactions (id, currency, description, posted_at)
                            VALUES (?, ?, ?, ?)
                            """,
                            transactionId,
                            "USD",
                            "Unbalanced header",
                            Timestamp.from(Instant.now()))))
        .isInstanceOf(RuntimeException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.ledger_transactions WHERE id = ?",
                Integer.class,
                transactionId))
        .isZero();
  }

  @Test
  void databaseRejectsAndRollsBackTwoUnequalEntriesAtCommit() {
    FundedWallet fundedWallet = fundedWallet("100.00");
    UUID transactionId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      jdbcTemplate.update(
                          "INSERT INTO ledgerx.ledger_transactions (id, currency, description, posted_at) VALUES (?, 'USD', ?, ?)",
                          transactionId,
                          "Unbalanced pair",
                          Timestamp.from(Instant.now()));
                      jdbcTemplate.update(
                          "INSERT INTO ledgerx.ledger_entries (id, ledger_transaction_id, line_number, ledger_account_id, side, amount, currency) VALUES (?, ?, 1, ?, 'DEBIT', 10.00, 'USD')",
                          UUID.randomUUID(),
                          transactionId,
                          fundedWallet.clearingAccountId());
                      jdbcTemplate.update(
                          "INSERT INTO ledgerx.ledger_entries (id, ledger_transaction_id, line_number, ledger_account_id, side, amount, currency) VALUES (?, ?, 2, ?, 'CREDIT', 9.99, 'USD')",
                          UUID.randomUUID(),
                          transactionId,
                          fundedWallet.walletAccountId());
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(countEntries(transactionId)).isZero();
    assertThat(transactionCount()).isEqualTo(1);
    assertThat(ledgerBalanceQueryService.balanceOf(fundedWallet.walletAccountId()).amount())
        .isEqualByComparingTo("100.00");
  }

  @Test
  void databasePreventsMutationOfPostedJournalHistory() {
    FundedWallet fundedWallet = fundedWallet("100.00");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.ledger_transactions SET description = ?",
                    "Changed after posting"))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.ledger_entries WHERE ledger_account_id = ?",
                Integer.class,
                fundedWallet.walletAccountId()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.ledger_entries SET amount = 1.00 WHERE ledger_account_id = ?",
                    fundedWallet.walletAccountId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM ledgerx.ledger_entries WHERE ledger_account_id = ?",
                    fundedWallet.walletAccountId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void onlyZeroBalanceWalletsCanCloseAndClosedWalletsCannotReceivePostings() {
    WalletRegistration emptyWallet = walletAccountService.createWallet(OwnerType.PERSON);

    walletAccountService.closeWallet(emptyWallet.walletAccountId());

    UUID clearingAccountId =
        walletAccountService.createSystemAccount(AccountType.ASSET, "CLOSING-TEST-CLEARING");
    assertThatThrownBy(
            () ->
                ledgerPostingService.post(
                    "Attempt funding a closed wallet",
                    List.of(
                        line(clearingAccountId, EntrySide.DEBIT, "1.00"),
                        line(emptyWallet.walletAccountId(), EntrySide.CREDIT, "1.00"))))
        .isInstanceOf(AccountUnavailableException.class);

    FundedWallet fundedWallet = fundedWallet("1.00");
    assertThatThrownBy(() -> walletAccountService.closeWallet(fundedWallet.walletAccountId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("only zero-balance accounts can close");
  }

  @Test
  void databasePreventsChangingWalletIdentityFields() {
    WalletRegistration wallet = walletAccountService.createWallet(OwnerType.PERSON);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE ledgerx.ledger_accounts SET owner_id = ? WHERE id = ?",
                    UUID.randomUUID(),
                    wallet.walletAccountId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void concurrentDebitsAllowOnlyOnePostingWhenCombinedAmountExceedsBalance() throws Exception {
    FundedWallet fundedWallet = fundedWallet("100.00");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(() -> attemptDebit(fundedWallet, ready, start, new BigDecimal("80.00")));
      Future<Boolean> second =
          executor.submit(() -> attemptDebit(fundedWallet, ready, start, new BigDecimal("80.00")));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      executor.shutdownNow();
    }

    assertThat(ledgerBalanceQueryService.balanceOf(fundedWallet.walletAccountId()).amount())
        .isEqualByComparingTo("20.00");
    assertThat(transactionCount()).isEqualTo(2);
    assertThat(unbalancedTransactionCount()).isZero();
  }

  private boolean attemptDebit(
      FundedWallet fundedWallet, CountDownLatch ready, CountDownLatch start, BigDecimal amount)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent test did not start");
    }

    try {
      ledgerPostingService.post(
          "Concurrent wallet debit",
          List.of(
              line(fundedWallet.walletAccountId(), EntrySide.DEBIT, amount.toPlainString()),
              line(fundedWallet.clearingAccountId(), EntrySide.CREDIT, amount.toPlainString())));
      return true;
    } catch (InsufficientFundsException exception) {
      return false;
    }
  }

  private boolean attemptWalletCreation(UUID ownerId, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent wallet creation did not start");
    }
    try {
      walletAccountService.createWalletForOwner(ownerId);
      return true;
    } catch (DataIntegrityViolationException exception) {
      return false;
    }
  }

  private FundedWallet fundedWallet(String amount) {
    WalletRegistration wallet = walletAccountService.createWallet(OwnerType.PERSON);
    UUID clearingAccountId =
        walletAccountService.createSystemAccount(
            AccountType.ASSET, "CLEARING-" + UUID.randomUUID());
    ledgerPostingService.post(
        "Initial funding",
        List.of(
            line(clearingAccountId, EntrySide.DEBIT, amount),
            line(wallet.walletAccountId(), EntrySide.CREDIT, amount)));
    return new FundedWallet(wallet.walletAccountId(), clearingAccountId);
  }

  private PostingLine line(UUID accountId, EntrySide side, String amount) {
    return new PostingLine(accountId, side, new Money(new BigDecimal(amount), CurrencyCode.USD));
  }

  private int countEntries(UUID transactionId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ledgerx.ledger_entries WHERE ledger_transaction_id = ?",
        Integer.class,
        transactionId);
  }

  private int transactionCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ledgerx.ledger_transactions", Integer.class);
  }

  private int unbalancedTransactionCount() {
    return jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM (
            SELECT ledger_transaction_id
            FROM ledgerx.ledger_entries
            GROUP BY ledger_transaction_id
            HAVING SUM(CASE WHEN side = 'DEBIT' THEN amount ELSE -amount END) <> 0
        ) AS unbalanced_transactions
        """,
        Integer.class);
  }

  private record FundedWallet(UUID walletAccountId, UUID clearingAccountId) {}
}
