package com.ledgerx.reliability;

import com.ledgerx.operations.OperationsConflictException;
import com.ledgerx.operations.OperationsNotFoundException;
import com.ledgerx.operations.OperationsValidationException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Audited operator request to republish an immutable outbox fact after broker loss or DLT triage.
 */
@Service
public class OutboxReplayService {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public OutboxReplayService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public ReplayRequest requestReplay(
      UUID eventId, String idempotencyKey, String reason, String operatorSubject) {
    if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 255) {
      throw new OperationsValidationException("a bounded Idempotency-Key is required");
    }
    if (!StringUtils.hasText(reason) || reason.length() > 500) {
      throw new OperationsValidationException("a bounded replay reason is required");
    }
    if (!StringUtils.hasText(operatorSubject) || operatorSubject.length() > 255) {
      throw new OperationsValidationException("operator subject is required");
    }
    List<String> statuses =
        jdbc.query(
            "SELECT status FROM ledgerx.outbox_events WHERE id = ? FOR UPDATE",
            (resultSet, rowNumber) -> resultSet.getString("status"),
            eventId);
    if (statuses.isEmpty()) {
      throw new OperationsNotFoundException("outbox event was not found");
    }
    List<ReplayRequest> existing =
        jdbc.query(
            """
            SELECT id, reason, operator_subject FROM ledgerx.outbox_replay_requests
            WHERE event_id = ? AND idempotency_key = ?
            """,
            (resultSet, rowNumber) -> {
              if (!reason.equals(resultSet.getString("reason"))
                  || !operatorSubject.equals(resultSet.getString("operator_subject"))) {
                throw new OperationsConflictException("replay key was used for another request");
              }
              return new ReplayRequest(resultSet.getObject("id", UUID.class), eventId, true);
            },
            eventId,
            idempotencyKey);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    if (!"PUBLISHED".equals(statuses.getFirst())) {
      throw new OperationsConflictException("only a published outbox event can be replayed");
    }

    UUID requestId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO ledgerx.outbox_replay_requests
            (id, event_id, operator_subject, idempotency_key, reason, requested_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        requestId,
        eventId,
        operatorSubject,
        idempotencyKey,
        reason,
        Timestamp.from(clock.instant()));
    int updated =
        jdbc.update(
            """
            UPDATE ledgerx.outbox_events
            SET status = 'PENDING', next_attempt_at = ?, published_at = NULL,
                last_error = NULL, replay_count = replay_count + 1, last_replay_request_id = ?
            WHERE id = ? AND status = 'PUBLISHED'
            """,
            Timestamp.from(clock.instant()),
            requestId,
            eventId);
    if (updated != 1) {
      throw new OperationsConflictException("outbox event could not be scheduled for replay");
    }
    return new ReplayRequest(requestId, eventId, false);
  }

  public record ReplayRequest(UUID requestId, UUID eventId, boolean replayed) {}
}
