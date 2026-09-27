package com.ledgerx.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.payment.application.PaymentApplicationService;
import com.ledgerx.payment.application.RefundApplicationService;
import com.ledgerx.payment.domain.PaymentCommand;
import com.ledgerx.payment.domain.RefundCommand;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "ledgerx.reconciliation.enabled=true")
@ActiveProfiles("test")
@Testcontainers
class ReconciliationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private ReconciliationRunner runner;
  @Autowired private WalletAccountService wallets;
  @Autowired private LedgerPostingService ledger;
  @Autowired private PaymentApplicationService payments;
  @Autowired private RefundApplicationService refunds;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Test
  void recordsJournalMismatchAndMissingIdempotencyWithoutRepair() {
    WalletRegistration source = wallets.createWallet(OwnerType.PERSON);
    WalletRegistration destination = wallets.createWallet(OwnerType.MERCHANT);
    UUID clearing = wallets.createSystemAccount(AccountType.ASSET, "RECON-" + UUID.randomUUID());
    ledger.post(
        "Fund source",
        List.of(
            new PostingLine(clearing, EntrySide.DEBIT, money("20.00")),
            new PostingLine(source.walletAccountId(), EntrySide.CREDIT, money("20.00"))));
    UUID journalId =
        ledger.post(
            "Transfer journal",
            List.of(
                new PostingLine(source.walletAccountId(), EntrySide.DEBIT, money("5.00")),
                new PostingLine(destination.walletAccountId(), EntrySide.CREDIT, money("5.00"))));
    UUID transferId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ledgerx.transfers
            (id, source_wallet_account_id, destination_wallet_account_id, amount, currency,
             ledger_transaction_id, completed_at)
        VALUES (?, ?, ?, 6.00, 'USD', ?, ?)
        """,
        transferId,
        source.walletAccountId(),
        destination.walletAccountId(),
        journalId,
        Timestamp.from(Instant.now()));

    runner.runOnce();

    assertThat(
            jdbc.queryForList(
                "SELECT finding_type FROM ledgerx.reconciliation_findings WHERE entity_id = ?",
                String.class,
                transferId))
        .contains("TRANSFER_JOURNAL_MISMATCH", "TRANSFER_IDEMPOTENCY_MISSING");
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM ledgerx.transfers WHERE id = ?", BigDecimal.class, transferId))
        .isEqualByComparingTo("6.00");
  }

  @Test
  void checkFailureLeavesDurableFailedRun() {
    jdbc.execute("ALTER TABLE ledgerx.refunds RENAME TO refunds_temporarily_hidden");
    try {
      assertThatThrownBy(runner::runOnce).isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.execute("ALTER TABLE ledgerx.refunds_temporarily_hidden RENAME TO refunds");
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM ledgerx.reconciliation_runs ORDER BY started_at DESC LIMIT 1",
                String.class))
        .isEqualTo("FAILED");
  }

  @Test
  void recordsOutboxSequenceAndPayloadCorruptionWithoutChangingTheEvent() {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ledgerx.outbox_events
          (id, aggregate_type, aggregate_id, aggregate_sequence, event_type, schema_version,
           payload, occurred_at, status, next_attempt_at, published_at)
        VALUES (?, 'PAYMENT', ?, 2, 'refund.completed.v1', 1,
                '{}'::jsonb, ?, 'PUBLISHED', ?, ?)
        """,
        eventId,
        aggregateId,
        Timestamp.from(Instant.now().minusSeconds(3600)),
        Timestamp.from(Instant.now().minusSeconds(3600)),
        Timestamp.from(Instant.now().minusSeconds(1800)));
    jdbc.update(
        """
        INSERT INTO ledgerx.processed_events
          (consumer_name, event_id, event_type, aggregate_id, payload_sha256, processed_at)
        VALUES ('payment-event-audit-v1', ?, 'refund.completed.v1', ?, ?, ?)
        """,
        eventId,
        aggregateId,
        "0".repeat(64),
        Timestamp.from(Instant.now()));

    runner.runOnce();

    assertThat(
            jdbc.queryForList(
                "SELECT finding_type FROM ledgerx.reconciliation_findings WHERE entity_id = ?",
                String.class,
                eventId))
        .contains(
            "OUTBOX_SEQUENCE_GAP",
            "OUTBOX_PAYLOAD_MISMATCH",
            "PAYMENT_EVENT_FACT_MISMATCH",
            "REFUND_EVENT_FACT_MISMATCH",
            "CONSUMER_RECEIPT_MISMATCH");
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_sequence FROM ledgerx.outbox_events WHERE id = ?",
                Long.class,
                eventId))
        .isEqualTo(2);
  }

  @Test
  void realPaymentAndRefundEventsMatchTheirCommittedFacts() {
    WalletRegistration payer = wallets.createWallet(OwnerType.PERSON);
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    UUID clearing = wallets.createSystemAccount(AccountType.ASSET, "FACT-" + UUID.randomUUID());
    ledger.post(
        "Fund payer",
        List.of(
            new PostingLine(clearing, EntrySide.DEBIT, money("20.00")),
            new PostingLine(payer.walletAccountId(), EntrySide.CREDIT, money("20.00"))));
    UUID paymentId =
        payments
            .create(
                new OwnerContext(payer.ownerId()),
                new PaymentCommand(
                    payer.walletAccountId(),
                    merchant.walletAccountId(),
                    money("10.00"),
                    "reconciliation-real-payment"))
            .payment()
            .id();
    refunds.create(
        new OwnerContext(merchant.ownerId()),
        new RefundCommand(paymentId, money("3.00"), "reconciliation-real-refund"));

    runner.runOnce();

    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM ledgerx.reconciliation_findings f
                WHERE f.entity_id IN (SELECT id FROM ledgerx.outbox_events WHERE aggregate_id = ?)
                  AND f.finding_type IN ('PAYMENT_EVENT_FACT_MISMATCH',
                                         'REFUND_EVENT_FACT_MISMATCH',
                                         'OUTBOX_PAYLOAD_MISMATCH')
                """,
                Integer.class,
                paymentId))
        .isZero();
  }

  private Money money(String amount) {
    return new Money(new BigDecimal(amount), CurrencyCode.USD);
  }
}
