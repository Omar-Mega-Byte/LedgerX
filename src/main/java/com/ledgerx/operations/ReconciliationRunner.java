package com.ledgerx.operations;

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

  private final JdbcTemplate jdbcTemplate;
  private final ReconciliationStore reconciliationStore;

  public ReconciliationRunner(JdbcTemplate jdbcTemplate, ReconciliationStore reconciliationStore) {
    this.jdbcTemplate = jdbcTemplate;
    this.reconciliationStore = reconciliationStore;
  }

  @Scheduled(cron = "${ledgerx.reconciliation.cron:0 15 * * * *}")
  public void runScheduled() {
    runOnce();
  }

  @Transactional
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
      reconciliationStore.completeRun(runId, findingCount);
    } catch (RuntimeException exception) {
      reconciliationStore.failRun(runId, "RECONCILIATION_FAILURE");
    }
  }

  private int recordUnbalancedJournals(UUID runId) {
    List<UUID> ids =
        jdbcTemplate.query(
            """
            SELECT transaction_id
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
            (resultSet, rowNumber) -> resultSet.getObject("transaction_id", UUID.class));
    ids.forEach(
        id ->
            record(
                runId,
                "UNBALANCED_JOURNAL",
                "CRITICAL",
                "LEDGER_TRANSACTION",
                id,
                "ledger journal is not balanced"));
    return ids.size();
  }

  private int recordPaymentsMissingOutboxEvent(UUID runId) {
    List<UUID> ids =
        jdbcTemplate.query(
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
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    ids.forEach(
        id ->
            record(
                runId,
                "PAYMENT_OUTBOX_EVENT_MISSING",
                "CRITICAL",
                "PAYMENT",
                id,
                "completed payment is missing its outbox event"));
    return ids.size();
  }

  private int recordRefundsMissingOutboxEvent(UUID runId) {
    List<UUID> ids =
        jdbcTemplate.query(
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
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    ids.forEach(
        id ->
            record(
                runId,
                "REFUND_OUTBOX_EVENT_MISSING",
                "CRITICAL",
                "REFUND",
                id,
                "completed refund is missing its outbox event"));
    return ids.size();
  }

  private int recordPaymentsMissingRiskAssessment(UUID runId) {
    List<UUID> ids =
        jdbcTemplate.query(
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
            ORDER BY p.completed_at, p.id LIMIT 1000
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
    ids.forEach(
        id ->
            record(
                runId,
                "PAYMENT_RISK_ASSESSMENT_MISSING",
                "CRITICAL",
                "PAYMENT",
                id,
                "completed payment under enabled policy is missing its risk assessment"));
    return ids.size();
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
