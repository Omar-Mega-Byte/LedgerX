package com.ledgerx.wallet.api;

import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.transfer.domain.WalletNotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bounded, owner-scoped history across immutable financial facts. */
@Validated
@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {

  private static final String WALLET_FILTER =
      "AND (source.id = :walletId OR destination.id = :walletId)";

  private final OwnerContextResolver ownerContextResolver;
  private final LedgerAccountRepository accountRepository;
  private final NamedParameterJdbcTemplate jdbcTemplate;

  public ActivityController(
      OwnerContextResolver ownerContextResolver,
      LedgerAccountRepository accountRepository,
      NamedParameterJdbcTemplate jdbcTemplate) {
    this.ownerContextResolver = ownerContextResolver;
    this.accountRepository = accountRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping
  @Transactional(readOnly = true)
  public List<ActivityResponse> list(
      @RequestHeader(value = "X-LedgerX-Owner-Id", required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @RequestParam(required = false) UUID walletId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page) {
    UUID ownerId = ownerContextResolver.resolve(ownerHeader, authenticatedToken).ownerId();
    if (walletId != null) {
      LedgerAccount wallet =
          accountRepository
              .findById(walletId)
              .orElseThrow(() -> new WalletNotFoundException("wallet was not found"));
      if (!wallet.isWallet() || !ownerId.equals(wallet.ownerId())) {
        throw new WalletNotFoundException("wallet was not found");
      }
    }

    String filter = walletId == null ? "" : WALLET_FILTER;
    String sql =
        """
        SELECT activity_kind, id, source_wallet_id, destination_wallet_id, amount, currency,
               occurred_at, ledger_transaction_id, direction
        FROM (
          SELECT 'TRANSFER' AS activity_kind, t.id, t.source_wallet_account_id AS source_wallet_id,
                 t.destination_wallet_account_id AS destination_wallet_id, t.amount, t.currency,
                 t.completed_at AS occurred_at, t.ledger_transaction_id,
                 CASE WHEN source.owner_id = :ownerId THEN 'OUT' ELSE 'IN' END AS direction
          FROM ledgerx.transfers t
          JOIN ledgerx.ledger_accounts source ON source.id = t.source_wallet_account_id
          JOIN ledgerx.ledger_accounts destination ON destination.id = t.destination_wallet_account_id
          WHERE (source.owner_id = :ownerId OR destination.owner_id = :ownerId) %s
          UNION ALL
          SELECT 'PAYMENT', p.id, p.payer_wallet_account_id, p.merchant_wallet_account_id,
                 p.amount, p.currency, p.completed_at, p.ledger_transaction_id,
                 CASE WHEN source.owner_id = :ownerId THEN 'OUT' ELSE 'IN' END
          FROM ledgerx.payments p
          JOIN ledgerx.ledger_accounts source ON source.id = p.payer_wallet_account_id
          JOIN ledgerx.ledger_accounts destination ON destination.id = p.merchant_wallet_account_id
          WHERE (source.owner_id = :ownerId OR destination.owner_id = :ownerId) %s
          UNION ALL
          SELECT 'REFUND', r.id, r.merchant_wallet_account_id, r.payer_wallet_account_id,
                 r.amount, r.currency, r.completed_at, r.ledger_transaction_id,
                 CASE WHEN source.owner_id = :ownerId THEN 'OUT' ELSE 'IN' END
          FROM ledgerx.refunds r
          JOIN ledgerx.ledger_accounts source ON source.id = r.merchant_wallet_account_id
          JOIN ledgerx.ledger_accounts destination ON destination.id = r.payer_wallet_account_id
          WHERE (source.owner_id = :ownerId OR destination.owner_id = :ownerId) %s
          UNION ALL
          SELECT 'TOP_UP', f.id, NULL::uuid, f.wallet_id, f.amount, f.currency,
                 f.completed_at, f.ledger_transaction_id, 'IN'
          FROM ledgerx.demo_fundings f
          WHERE f.owner_id = :ownerId AND f.ledger_transaction_id IS NOT NULL %s
        ) activity
        ORDER BY occurred_at DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
            .formatted(
                filter, filter, filter, walletId == null ? "" : "AND f.wallet_id = :walletId");
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("ownerId", ownerId)
            .addValue("walletId", walletId, Types.OTHER)
            .addValue("limit", limit)
            .addValue("offset", page * limit);
    return jdbcTemplate.query(
        sql,
        parameters,
        (resultSet, rowNumber) ->
            new ActivityResponse(
                resultSet.getString("activity_kind"),
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("source_wallet_id", UUID.class),
                resultSet.getObject("destination_wallet_id", UUID.class),
                resultSet.getBigDecimal("amount").toPlainString(),
                resultSet.getString("currency"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getObject("ledger_transaction_id", UUID.class),
                resultSet.getString("direction")));
  }

  public record ActivityResponse(
      String kind,
      UUID id,
      UUID sourceWalletId,
      UUID destinationWalletId,
      String amount,
      String currency,
      Instant occurredAt,
      UUID ledgerTransactionId,
      String direction) {}
}
