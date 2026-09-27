package com.ledgerx.operations;

import com.ledgerx.reliability.KafkaProperties;
import com.ledgerx.webhook.WebhookProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only consistency checks that write immutable evidence but never repair financial data. */
@Component
@ConditionalOnProperty(prefix = "ledgerx.reconciliation", name = "enabled", havingValue = "true")
public class ReconciliationRunner {

  private static final int FINDING_PAGE_SIZE = 250;
  private static final int MAX_FINDINGS_PER_CHECK = 10_000;

  private final JdbcTemplate jdbcTemplate;
  private final ReconciliationStore reconciliationStore;
  private final KafkaProperties kafkaProperties;
  private final WebhookProperties webhookProperties;

  public ReconciliationRunner(
      JdbcTemplate jdbcTemplate,
      ReconciliationStore reconciliationStore,
      KafkaProperties kafkaProperties,
      WebhookProperties webhookProperties) {
    this.jdbcTemplate = jdbcTemplate;
    this.reconciliationStore = reconciliationStore;
    this.kafkaProperties = kafkaProperties;
    this.webhookProperties = webhookProperties;
  }

  @Scheduled(cron = "${ledgerx.reconciliation.cron:0 15 * * * *}")
  public void runScheduled() {
    runOnce();
  }

  @Transactional(timeout = 300)
  public void runOnce() {
    Boolean lockAcquired =
        jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(91225401)", Boolean.class);
    if (!Boolean.TRUE.equals(lockAcquired)) {
      return;
    }
    UUID runId = reconciliationStore.startRun();
    try {
      int findingCount = 0;
      findingCount += recordUnbalancedJournals(runId);
      findingCount += recordPaymentsMissingOutboxEvent(runId);
      findingCount += recordRefundsMissingOutboxEvent(runId);
      findingCount += recordPaymentsMissingRiskAssessment(runId);
      findingCount += recordNegativeWalletBalances(runId);
      findingCount += recordTransferJournalMismatches(runId);
      findingCount += recordPaymentJournalMismatches(runId);
      findingCount += recordRefundJournalMismatches(runId);
      findingCount += recordOverRefundedPayments(runId);
      findingCount += recordStaleOutboxEvents(runId);
      findingCount += recordOutboxSequenceGaps(runId);
      findingCount += recordOutboxPayloadMismatches(runId);
      findingCount += recordPaymentEventFactMismatches(runId);
      findingCount += recordRefundEventFactMismatches(runId);
      findingCount += recordPublishedEventsWithoutReceipts(runId);
      findingCount += recordProcessedReceiptMismatches(runId);
      findingCount += recordDeadWebhookDeliveries(runId);
      findingCount += recordStalledWebhookDeliveries(runId);
      findingCount += recordWebhookEventMismatches(runId);
      findingCount += recordWebhookAttemptsMissing(runId);
      findingCount += recordUnlinkedTransfers(runId);
      findingCount += recordUnlinkedPayments(runId);
      findingCount += recordUnlinkedRefunds(runId);
      reconciliationStore.completeRun(runId, findingCount);
    } catch (RuntimeException exception) {
      reconciliationStore.failRun(runId, "RECONCILIATION_FAILURE");
      throw exception;
    }
  }

  private int recordUnbalancedJournals(UUID runId) {
    return recordQuery(
        runId,
        """
            SELECT transaction_id AS id
            FROM (
                SELECT t.id AS transaction_id,
                       COUNT(e.id) AS entry_count,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0) AS debit_total,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0) AS credit_total
                FROM ledgerx.ledger_transactions t
                LEFT JOIN ledgerx.ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id
            ) journal
            WHERE entry_count < 2 OR debit_total <> credit_total
            """,
        "UNBALANCED_JOURNAL",
        "CRITICAL",
        "LEDGER_TRANSACTION",
        "ledger journal is not balanced");
  }

  private int recordPaymentsMissingOutboxEvent(UUID runId) {
    return recordQuery(
        runId,
        """
            SELECT p.id
            FROM ledgerx.payments p
            WHERE NOT EXISTS (
                SELECT 1
                FROM ledgerx.outbox_events e
                WHERE e.aggregate_type = 'PAYMENT'
                  AND e.aggregate_id = p.id
                  AND e.event_type = 'payment.completed.v1'
            )
            """,
        "PAYMENT_OUTBOX_EVENT_MISSING",
        "CRITICAL",
        "PAYMENT",
        "completed payment is missing its outbox event");
  }

  private int recordRefundsMissingOutboxEvent(UUID runId) {
    return recordQuery(
        runId,
        """
            SELECT r.id
            FROM ledgerx.refunds r
            JOIN ledgerx.payments p ON p.id = r.payment_id
            WHERE NOT EXISTS (
                SELECT 1
                FROM ledgerx.outbox_events e
                WHERE e.aggregate_type = 'PAYMENT'
                  AND e.aggregate_id = p.id
                  AND e.event_type = 'refund.completed.v1'
                  AND e.payload -> 'data' ->> 'refundId' = r.id::text
            )
            """,
        "REFUND_OUTBOX_EVENT_MISSING",
        "CRITICAL",
        "REFUND",
        "completed refund is missing its outbox event");
  }

  private int recordPaymentsMissingRiskAssessment(UUID runId) {
    return recordQuery(
        runId,
        """
            SELECT p.id FROM ledgerx.payments p
            WHERE EXISTS (
                SELECT 1 FROM ledgerx.risk_policy_versions policy
                WHERE policy.enabled = TRUE AND policy.created_at <= p.completed_at
                  AND NOT EXISTS (
                    SELECT 1 FROM ledgerx.risk_policy_versions newer
                    WHERE newer.version_number > policy.version_number
                      AND newer.created_at <= p.completed_at
                  )
            )
              AND NOT EXISTS (
                SELECT 1 FROM ledgerx.risk_assessments assessment
                WHERE assessment.payment_id = p.id AND assessment.outcome = 'ALLOW'
              )
            """,
        "PAYMENT_RISK_ASSESSMENT_MISSING",
        "CRITICAL",
        "PAYMENT",
        "completed payment under enabled policy is missing its risk assessment");
  }

  private int recordNegativeWalletBalances(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT a.id
        FROM ledgerx.ledger_accounts a
        LEFT JOIN ledgerx.ledger_entries e ON e.ledger_account_id = a.id
        WHERE a.account_kind = 'WALLET'
        GROUP BY a.id
        HAVING COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) < 0
        """,
        "NEGATIVE_WALLET_BALANCE",
        "CRITICAL",
        "LEDGER_ACCOUNT",
        "wallet derived balance is negative");
  }

  private int recordTransferJournalMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT t.id FROM ledgerx.transfers t
        LEFT JOIN ledgerx.ledger_entries e ON e.ledger_transaction_id = t.ledger_transaction_id
        GROUP BY t.id, t.source_wallet_account_id, t.destination_wallet_account_id, t.amount
        HAVING COUNT(e.id) <> 2
           OR COALESCE(SUM(e.amount) FILTER (
                WHERE e.ledger_account_id = t.source_wallet_account_id AND e.side = 'DEBIT'), 0) <> t.amount
           OR COALESCE(SUM(e.amount) FILTER (
                WHERE e.ledger_account_id = t.destination_wallet_account_id AND e.side = 'CREDIT'), 0) <> t.amount
        """,
        "TRANSFER_JOURNAL_MISMATCH",
        "CRITICAL",
        "TRANSFER",
        "transfer does not match its posted journal");
  }

  private int recordPaymentJournalMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT p.id FROM ledgerx.payments p
        LEFT JOIN ledgerx.ledger_entries e ON e.ledger_transaction_id = p.ledger_transaction_id
        GROUP BY p.id, p.payer_wallet_account_id, p.merchant_wallet_account_id, p.amount
        HAVING COUNT(e.id) <> 2
           OR COALESCE(SUM(e.amount) FILTER (
                WHERE e.ledger_account_id = p.payer_wallet_account_id AND e.side = 'DEBIT'), 0) <> p.amount
           OR COALESCE(SUM(e.amount) FILTER (
                WHERE e.ledger_account_id = p.merchant_wallet_account_id AND e.side = 'CREDIT'), 0) <> p.amount
        """,
        "PAYMENT_JOURNAL_MISMATCH",
        "CRITICAL",
        "PAYMENT",
        "payment does not match its posted journal");
  }

  private int recordRefundJournalMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT r.id FROM ledgerx.refunds r
        LEFT JOIN ledgerx.ledger_entries e ON e.ledger_transaction_id = r.ledger_transaction_id
        GROUP BY r.id, r.merchant_wallet_account_id, r.payer_wallet_account_id, r.amount
        HAVING COUNT(e.id) <> 2
           OR COALESCE(SUM(e.amount) FILTER (
                WHERE e.ledger_account_id = r.merchant_wallet_account_id AND e.side = 'DEBIT'), 0) <> r.amount
           OR COALESCE(SUM(e.amount) FILTER (
                WHERE e.ledger_account_id = r.payer_wallet_account_id AND e.side = 'CREDIT'), 0) <> r.amount
        """,
        "REFUND_JOURNAL_MISMATCH",
        "CRITICAL",
        "REFUND",
        "refund does not match its posted journal");
  }

  private int recordOverRefundedPayments(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT p.id FROM ledgerx.payments p
        JOIN ledgerx.refunds r ON r.payment_id = p.id
        GROUP BY p.id, p.amount
        HAVING SUM(r.amount) > p.amount
        """,
        "PAYMENT_OVER_REFUNDED",
        "CRITICAL",
        "PAYMENT",
        "refund total exceeds completed payment amount");
  }

  private int recordStaleOutboxEvents(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT id FROM ledgerx.outbox_events
        WHERE (status = 'PENDING' AND next_attempt_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
           OR (status = 'IN_FLIGHT' AND lease_until < CURRENT_TIMESTAMP)
        """,
        "OUTBOX_EVENT_STALLED",
        "ERROR",
        "OUTBOX_EVENT",
        "outbox event has not progressed within its expected retry window");
  }

  private int recordOutboxSequenceGaps(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT e.id FROM ledgerx.outbox_events e
        WHERE (e.aggregate_sequence > 1 AND NOT EXISTS (
            SELECT 1 FROM ledgerx.outbox_events previous
            WHERE previous.aggregate_type = e.aggregate_type
              AND previous.aggregate_id = e.aggregate_id
              AND previous.aggregate_sequence = e.aggregate_sequence - 1
        )) OR (e.aggregate_sequence = 1 AND e.event_type <> 'payment.completed.v1')
        """,
        "OUTBOX_SEQUENCE_GAP",
        "CRITICAL",
        "OUTBOX_EVENT",
        "payment event stream has a missing or invalid predecessor");
  }

  private int recordOutboxPayloadMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT id FROM ledgerx.outbox_events
        WHERE payload ->> 'eventId' IS DISTINCT FROM id::text
           OR payload ->> 'eventType' IS DISTINCT FROM event_type
           OR payload ->> 'aggregateId' IS DISTINCT FROM aggregate_id::text
           OR payload ->> 'aggregateSequence' IS DISTINCT FROM aggregate_sequence::text
           OR payload ->> 'schemaVersion' IS DISTINCT FROM schema_version::text
        """,
        "OUTBOX_PAYLOAD_MISMATCH",
        "CRITICAL",
        "OUTBOX_EVENT",
        "immutable event payload disagrees with its outbox metadata");
  }

  private int recordPaymentEventFactMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT e.id FROM ledgerx.outbox_events e
        LEFT JOIN ledgerx.payments p ON p.id = e.aggregate_id
        WHERE p.id IS NULL
           OR e.payload -> 'data' ->> 'paymentId' IS DISTINCT FROM p.id::text
           OR e.payload -> 'data' ->> 'payerWalletId' IS DISTINCT FROM p.payer_wallet_account_id::text
           OR e.payload -> 'data' ->> 'merchantWalletId' IS DISTINCT FROM p.merchant_wallet_account_id::text
           OR e.payload -> 'data' ->> 'amount' IS DISTINCT FROM p.amount::text
           OR e.payload -> 'data' ->> 'currency' IS DISTINCT FROM p.currency
           OR e.payload -> 'data' ->> 'ledgerTransactionId' IS DISTINCT FROM p.ledger_transaction_id::text
           OR (e.event_type = 'payment.completed.v1' AND e.occurred_at IS DISTINCT FROM p.completed_at)
        """,
        "PAYMENT_EVENT_FACT_MISMATCH",
        "CRITICAL",
        "OUTBOX_EVENT",
        "event payment data disagrees with the committed payment fact");
  }

  private int recordRefundEventFactMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT e.id FROM ledgerx.outbox_events e
        LEFT JOIN ledgerx.refunds r
          ON r.id::text = e.payload -> 'data' ->> 'refundId'
         AND r.payment_id = e.aggregate_id
        WHERE e.event_type = 'refund.completed.v1'
          AND (r.id IS NULL
            OR e.payload -> 'data' ->> 'refundLedgerTransactionId' IS DISTINCT FROM r.ledger_transaction_id::text
            OR e.payload -> 'data' ->> 'refundAmount' IS DISTINCT FROM r.amount::text
            OR e.occurred_at IS DISTINCT FROM r.completed_at)
        """,
        "REFUND_EVENT_FACT_MISMATCH",
        "CRITICAL",
        "OUTBOX_EVENT",
        "event refund data disagrees with the committed refund fact");
  }

  private int recordPublishedEventsWithoutReceipts(UUID runId) {
    int findings = 0;
    if (kafkaProperties.isConsumerEnabled()) {
      findings +=
          recordMissingReceipt(runId, "payment-event-audit-v1", "KAFKA_AUDIT_RECEIPT_MISSING");
    }
    if (webhookProperties.isConsumerEnabled()) {
      findings +=
          recordMissingReceipt(
              runId, "webhook-delivery-enqueuer-v1", "WEBHOOK_ENQUEUE_RECEIPT_MISSING");
    }
    return findings;
  }

  private int recordMissingReceipt(UUID runId, String consumerName, String findingType) {
    return recordQuery(
        runId,
        """
        SELECT e.id FROM ledgerx.outbox_events e
        WHERE e.status = 'PUBLISHED'
          AND e.published_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
          AND NOT EXISTS (
              SELECT 1 FROM ledgerx.processed_events receipt
              WHERE receipt.event_id = e.id AND receipt.consumer_name = '%s'
          )
        """
            .formatted(consumerName),
        findingType,
        "ERROR",
        "OUTBOX_EVENT",
        "published event has no durable consumer receipt after the grace period");
  }

  private int recordProcessedReceiptMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT DISTINCT receipt.event_id AS id
        FROM ledgerx.processed_events receipt
        LEFT JOIN ledgerx.outbox_events e ON e.id = receipt.event_id
        WHERE e.id IS NULL
           OR receipt.event_type IS DISTINCT FROM e.event_type
           OR receipt.aggregate_id IS DISTINCT FROM e.aggregate_id
           OR receipt.payload_sha256 IS DISTINCT FROM
              encode(sha256(convert_to(e.payload::text, 'UTF8')), 'hex')
        """,
        "CONSUMER_RECEIPT_MISMATCH",
        "CRITICAL",
        "OUTBOX_EVENT",
        "consumer receipt has no matching immutable event payload");
  }

  private int recordDeadWebhookDeliveries(UUID runId) {
    return recordQuery(
        runId,
        "SELECT id FROM ledgerx.webhook_deliveries WHERE status = 'DEAD'",
        "WEBHOOK_DELIVERY_DEAD",
        "WARNING",
        "WEBHOOK_DELIVERY",
        "webhook delivery requires merchant or operator review");
  }

  private int recordStalledWebhookDeliveries(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT d.id FROM ledgerx.webhook_deliveries d
        JOIN ledgerx.webhook_endpoints e ON e.id = d.webhook_endpoint_id
        WHERE (d.status = 'PENDING' AND e.status = 'ACTIVE'
               AND d.next_attempt_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
           OR (d.status = 'IN_FLIGHT' AND d.lease_until < CURRENT_TIMESTAMP)
        """,
        "WEBHOOK_DELIVERY_STALLED",
        "ERROR",
        "WEBHOOK_DELIVERY",
        "webhook delivery has not progressed within its expected retry window");
  }

  private int recordWebhookEventMismatches(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT d.id FROM ledgerx.webhook_deliveries d
        LEFT JOIN ledgerx.outbox_events e ON e.id = d.event_id
        WHERE e.id IS NULL
           OR d.aggregate_id IS DISTINCT FROM e.aggregate_id
           OR d.aggregate_sequence IS DISTINCT FROM e.aggregate_sequence
           OR d.event_type IS DISTINCT FROM e.event_type
           OR d.schema_version IS DISTINCT FROM e.schema_version
           OR d.payload_sha256 IS DISTINCT FROM encode(sha256(convert_to(d.payload, 'UTF8')), 'hex')
        """,
        "WEBHOOK_EVENT_MISMATCH",
        "CRITICAL",
        "WEBHOOK_DELIVERY",
        "webhook delivery does not match its event or immutable payload hash");
  }

  private int recordWebhookAttemptsMissing(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT d.id FROM ledgerx.webhook_deliveries d
        WHERE d.status IN ('DELIVERED', 'DEAD')
          AND NOT EXISTS (
              SELECT 1 FROM ledgerx.webhook_delivery_attempts a
              WHERE a.webhook_delivery_id = d.id AND a.replay_count = d.replay_count
          )
        """,
        "WEBHOOK_ATTEMPT_EVIDENCE_MISSING",
        "CRITICAL",
        "WEBHOOK_DELIVERY",
        "terminal webhook delivery lacks attempt evidence");
  }

  private int recordUnlinkedTransfers(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT t.id FROM ledgerx.transfers t
        WHERE NOT EXISTS (SELECT 1 FROM ledgerx.transfer_idempotency i
                          WHERE i.transfer_id = t.id AND i.state = 'COMPLETED')
        """,
        "TRANSFER_IDEMPOTENCY_MISSING",
        "CRITICAL",
        "TRANSFER",
        "completed transfer has no completed idempotency record");
  }

  private int recordUnlinkedPayments(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT p.id FROM ledgerx.payments p
        WHERE NOT EXISTS (SELECT 1 FROM ledgerx.payment_idempotency i
                          WHERE i.payment_id = p.id AND i.state = 'COMPLETED')
        """,
        "PAYMENT_IDEMPOTENCY_MISSING",
        "CRITICAL",
        "PAYMENT",
        "completed payment has no completed idempotency record");
  }

  private int recordUnlinkedRefunds(UUID runId) {
    return recordQuery(
        runId,
        """
        SELECT r.id FROM ledgerx.refunds r
        WHERE NOT EXISTS (SELECT 1 FROM ledgerx.refund_idempotency i
                          WHERE i.refund_id = r.id AND i.state = 'COMPLETED')
        """,
        "REFUND_IDEMPOTENCY_MISSING",
        "CRITICAL",
        "REFUND",
        "completed refund has no completed idempotency record");
  }

  private int recordQuery(
      UUID runId,
      String sql,
      String findingType,
      String severity,
      String entityType,
      String message) {
    int count = 0;
    UUID cursor = null;
    while (true) {
      List<UUID> ids =
          jdbcTemplate.query(
              "SELECT findings.id FROM ("
                  + sql
                  + ") findings "
                  + "WHERE (CAST(? AS UUID) IS NULL OR findings.id > CAST(? AS UUID)) "
                  + "ORDER BY findings.id LIMIT ?",
              (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
              cursor,
              cursor,
              FINDING_PAGE_SIZE);
      if (ids.isEmpty()) {
        return count;
      }
      if (count + ids.size() > MAX_FINDINGS_PER_CHECK) {
        throw new IllegalStateException("reconciliation finding limit exceeded for " + findingType);
      }
      ids.forEach(id -> record(runId, findingType, severity, entityType, id, message));
      count += ids.size();
      cursor = ids.getLast();
    }
  }

  private void record(
      UUID runId,
      String findingType,
      String severity,
      String entityType,
      UUID entityId,
      String detail) {
    reconciliationStore.recordFinding(
        runId,
        findingType,
        severity,
        entityType,
        entityId,
        fingerprint(findingType + ":" + entityType + ":" + entityId),
        "{\"message\":\"" + detail + "\"}");
  }

  private String fingerprint(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }
}
