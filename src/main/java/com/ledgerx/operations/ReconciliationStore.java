package com.ledgerx.operations;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists immutable reconciliation evidence; it intentionally has no financial write methods. */
@Repository
public class ReconciliationStore {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public ReconciliationStore(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  public UUID startRun() {
    UUID runId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.reconciliation_runs (
            id, check_version, started_at, completed_at, status, finding_count, failure_category
        )
        VALUES (?, 1, ?, NULL, 'RUNNING', 0, NULL)
        """,
        runId,
        Timestamp.from(clock.instant()));
    return runId;
  }

  public void recordFinding(
      UUID runId,
      String findingType,
      String severity,
      String entityType,
      UUID entityId,
      String fingerprint,
      String detailsJson) {
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.reconciliation_findings (
            id, reconciliation_run_id, finding_type, severity, entity_type, entity_id, fingerprint,
            details, detected_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
        ON CONFLICT (reconciliation_run_id, fingerprint) DO NOTHING
        """,
        UUID.randomUUID(),
        runId,
        findingType,
        severity,
        entityType,
        entityId,
        fingerprint,
        detailsJson,
        Timestamp.from(clock.instant()));
  }

  public void completeRun(UUID runId, int findingCount) {
    updateRun(runId, "COMPLETED", findingCount, null);
  }

  public void failRun(UUID runId, String failureCategory) {
    updateRun(runId, "FAILED", 0, failureCategory);
  }

  private void updateRun(UUID runId, String status, int findingCount, String failureCategory) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.reconciliation_runs
            SET status = ?, completed_at = ?, finding_count = ?, failure_category = ?
            WHERE id = ? AND status = 'RUNNING'
            """,
            status,
            Timestamp.from(clock.instant()),
            findingCount,
            failureCategory,
            runId);
    if (updated != 1) {
      throw new IllegalStateException("reconciliation run completion was lost");
    }
  }
}
