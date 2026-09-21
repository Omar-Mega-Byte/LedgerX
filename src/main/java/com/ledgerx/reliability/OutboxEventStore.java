package com.ledgerx.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL outbox persistence with short claim/acknowledgement transactions. */
@Repository
public class OutboxEventStore {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public OutboxEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public long nextAggregateSequence(UUID aggregateId) {
    Long nextSequence =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(MAX(aggregate_sequence), 0) + 1
            FROM ledgerx.outbox_events
            WHERE aggregate_type = 'PAYMENT' AND aggregate_id = ?
            """,
            Long.class,
            aggregateId);
    if (nextSequence == null) {
      throw new IllegalStateException("outbox aggregate sequence was not returned");
    }
    return nextSequence;
  }

  public void append(PaymentEventEnvelope envelope) {
    String payload;
    try {
      payload = objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("payment event payload could not be serialized", exception);
    }
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.outbox_events (
            id, aggregate_type, aggregate_id, aggregate_sequence, event_type, schema_version, payload,
            occurred_at, status, attempt_count, next_attempt_at, lease_token, lease_until, published_at,
            last_error
        )
        VALUES (?, 'PAYMENT', ?, ?, ?, ?, CAST(? AS JSONB), ?, 'PENDING', 0, ?, NULL, NULL, NULL, NULL)
        """,
        envelope.eventId(),
        envelope.aggregateId(),
        envelope.aggregateSequence(),
        envelope.eventType(),
        envelope.schemaVersion(),
        payload,
        Timestamp.from(envelope.occurredAt()),
        Timestamp.from(envelope.occurredAt()));
  }

  @Transactional
  public Optional<OutboxEvent> claimNext(Instant now, Duration leaseDuration) {
    releaseExpiredLeases(now);
    List<OutboxEvent> candidates =
        jdbcTemplate.query(
            """
            SELECT id, aggregate_id, aggregate_sequence, event_type, payload::text, occurred_at, status,
                   attempt_count, lease_token
            FROM ledgerx.outbox_events candidate
            WHERE candidate.status = 'PENDING'
              AND candidate.next_attempt_at <= ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM ledgerx.outbox_events previous
                  WHERE previous.aggregate_type = candidate.aggregate_type
                    AND previous.aggregate_id = candidate.aggregate_id
                    AND previous.aggregate_sequence < candidate.aggregate_sequence
                    AND previous.status <> 'PUBLISHED'
              )
            ORDER BY candidate.occurred_at, candidate.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
            (resultSet, rowNumber) ->
                new OutboxEvent(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("aggregate_id", UUID.class),
                    resultSet.getLong("aggregate_sequence"),
                    PaymentEventType.fromWireName(resultSet.getString("event_type")),
                    resultSet.getString("payload"),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    OutboxStatus.valueOf(resultSet.getString("status")),
                    resultSet.getInt("attempt_count"),
                    resultSet.getObject("lease_token", UUID.class)),
            Timestamp.from(now));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    OutboxEvent candidate = candidates.getFirst();
    UUID leaseToken = UUID.randomUUID();
    Instant leaseUntil = now.plus(leaseDuration);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.outbox_events
            SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1, lease_token = ?, lease_until = ?,
                last_error = NULL
            WHERE id = ? AND status = 'PENDING'
            """,
            leaseToken,
            Timestamp.from(leaseUntil),
            candidate.id());
    if (updated != 1) {
      throw new IllegalStateException("outbox claim did not update exactly one event");
    }
    return Optional.of(
        new OutboxEvent(
            candidate.id(),
            candidate.aggregateId(),
            candidate.aggregateSequence(),
            candidate.eventType(),
            candidate.payload(),
            candidate.occurredAt(),
            OutboxStatus.IN_FLIGHT,
            candidate.attemptCount() + 1,
            leaseToken));
  }

  @Transactional
  public void markPublished(OutboxEvent event, Instant publishedAt) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.outbox_events
            SET status = 'PUBLISHED', lease_token = NULL, lease_until = NULL, published_at = ?, last_error = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
            """,
            Timestamp.from(publishedAt),
            event.id(),
            event.leaseToken());
    if (updated != 1) {
      throw new IllegalStateException("outbox publication acknowledgement lost its lease");
    }
  }

  @Transactional
  public void scheduleRetry(OutboxEvent event, Instant nextAttemptAt, String error) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.outbox_events
            SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = ?, last_error = ?
            WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
            """,
            Timestamp.from(nextAttemptAt),
            truncate(error),
            event.id(),
            event.leaseToken());
    if (updated != 1) {
      throw new IllegalStateException("outbox retry scheduling lost its lease");
    }
  }

  private void releaseExpiredLeases(Instant now) {
    jdbcTemplate.update(
        """
        UPDATE ledgerx.outbox_events
        SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = ?,
            last_error = 'publisher lease expired'
        WHERE status = 'IN_FLIGHT' AND lease_until <= ?
        """,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private String truncate(String error) {
    if (error == null || error.isBlank()) {
      return "publication failed";
    }
    return error.length() <= 500 ? error : error.substring(0, 500);
  }
}
