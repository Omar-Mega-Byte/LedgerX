package com.ledgerx.webhook;

import com.ledgerx.crypto.Sha256;
import com.ledgerx.reliability.PaymentEventEnvelope;
import com.ledgerx.reliability.PaymentEventType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable outbound work queue with short PostgreSQL leases and append-only attempt evidence. */
@Repository
public class WebhookDeliveryStore {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public WebhookDeliveryStore(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  public void enqueue(WebhookEndpoint endpoint, PaymentEventEnvelope envelope, String payload) {
    Instant now = clock.instant();
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.webhook_deliveries (
            id, webhook_endpoint_id, event_id, aggregate_id, aggregate_sequence, event_type, schema_version,
            payload, payload_sha256, queued_at, status, attempt_count, replay_count, next_attempt_at,
            lease_token, lease_until, delivered_at, last_http_status, last_error
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, ?, NULL, NULL, NULL, NULL, NULL)
        ON CONFLICT (webhook_endpoint_id, event_id) DO NOTHING
        """,
        UUID.randomUUID(),
        endpoint.id(),
        envelope.eventId(),
        envelope.aggregateId(),
        envelope.aggregateSequence(),
        envelope.eventType(),
        envelope.schemaVersion(),
        payload,
        Sha256.hexUtf8(payload),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  @Transactional
  public Optional<WebhookClaim> claimNext(Instant now, Duration leaseDuration) {
    releaseExpiredLeases(now);
    List<WebhookClaim> candidates =
        jdbcTemplate.query(
            """
            SELECT d.id, d.webhook_endpoint_id, d.event_id, d.aggregate_id, d.aggregate_sequence,
                   d.event_type, d.schema_version, d.payload, d.payload_sha256, d.queued_at, d.status,
                   d.attempt_count, d.replay_count, d.next_attempt_at, d.lease_token, d.lease_until,
                   d.delivered_at, d.last_http_status, d.last_error,
                   e.target_url, e.secret_ciphertext, e.secret_key_version
            FROM ledgerx.webhook_deliveries d
            JOIN ledgerx.webhook_endpoints e ON e.id = d.webhook_endpoint_id
            WHERE d.status = 'PENDING'
              AND d.next_attempt_at <= ?
              AND e.status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM ledgerx.webhook_deliveries previous
                  WHERE previous.webhook_endpoint_id = d.webhook_endpoint_id
                    AND previous.aggregate_id = d.aggregate_id
                    AND previous.aggregate_sequence < d.aggregate_sequence
                    AND previous.status <> 'DELIVERED'
              )
            ORDER BY d.queued_at, d.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
            (resultSet, rowNumber) ->
                new WebhookClaim(
                    mapDelivery(resultSet),
                    resultSet.getString("target_url"),
                    resultSet.getBytes("secret_ciphertext"),
                    resultSet.getInt("secret_key_version")),
            Timestamp.from(now));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    WebhookClaim candidate = candidates.getFirst();
    UUID leaseToken = UUID.randomUUID();
    Instant leaseUntil = now.plus(leaseDuration);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_deliveries
            SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1, lease_token = ?, lease_until = ?,
                last_error = NULL
            WHERE id = ? AND status = 'PENDING'
            """,
            leaseToken,
            Timestamp.from(leaseUntil),
            candidate.delivery().id());
    if (updated != 1) {
      throw new IllegalStateException("webhook delivery claim was lost");
    }
    WebhookDelivery claimed = candidate.delivery();
    return Optional.of(
        new WebhookClaim(
            new WebhookDelivery(
                claimed.id(),
                claimed.webhookEndpointId(),
                claimed.eventId(),
                claimed.aggregateId(),
                claimed.aggregateSequence(),
                claimed.eventType(),
                claimed.schemaVersion(),
                claimed.payload(),
                claimed.payloadHash(),
                claimed.queuedAt(),
                WebhookDeliveryStatus.IN_FLIGHT,
                claimed.attemptCount() + 1,
                claimed.replayCount(),
                claimed.nextAttemptAt(),
                leaseToken,
                leaseUntil,
                null,
                claimed.lastHttpStatus(),
                null),
            candidate.targetUrl(),
            candidate.secretCiphertext(),
            candidate.secretKeyVersion()));
  }

  @Transactional
  public void markDelivered(
      WebhookClaim claim, Instant startedAt, Instant completedAt, int httpStatus) {
    appendAttempt(
        claim.delivery(),
        startedAt,
        completedAt,
        WebhookDeliveryOutcome.DELIVERED,
        httpStatus,
        null);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_deliveries
            SET status = 'DELIVERED', lease_token = NULL, lease_until = NULL, delivered_at = ?,
                last_http_status = ?, last_error = NULL
            WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
            """,
            Timestamp.from(completedAt),
            httpStatus,
            claim.delivery().id(),
            claim.leaseToken());
    requireLeaseUpdate(updated);
  }

  @Transactional
  public void scheduleRetry(
      WebhookClaim claim,
      Instant startedAt,
      Instant completedAt,
      Integer httpStatus,
      String errorCategory,
      Instant nextAttemptAt) {
    appendAttempt(
        claim.delivery(),
        startedAt,
        completedAt,
        WebhookDeliveryOutcome.RETRYABLE_FAILURE,
        httpStatus,
        errorCategory);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_deliveries
            SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = ?,
                last_http_status = ?, last_error = ?
            WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
            """,
            Timestamp.from(nextAttemptAt),
            httpStatus,
            truncate(errorCategory),
            claim.delivery().id(),
            claim.leaseToken());
    requireLeaseUpdate(updated);
  }

  @Transactional
  public void markDead(
      WebhookClaim claim,
      Instant startedAt,
      Instant completedAt,
      Integer httpStatus,
      String errorCategory) {
    appendAttempt(
        claim.delivery(),
        startedAt,
        completedAt,
        WebhookDeliveryOutcome.TERMINAL_FAILURE,
        httpStatus,
        errorCategory);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_deliveries
            SET status = 'DEAD', lease_token = NULL, lease_until = NULL, last_http_status = ?, last_error = ?
            WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
            """,
            httpStatus,
            truncate(errorCategory),
            claim.delivery().id(),
            claim.leaseToken());
    requireLeaseUpdate(updated);
  }

  public Optional<WebhookDelivery> findByIdForOwner(UUID deliveryId, UUID ownerId) {
    return jdbcTemplate
        .query(
            """
            SELECT d.id, d.webhook_endpoint_id, d.event_id, d.aggregate_id, d.aggregate_sequence,
                   d.event_type, d.schema_version, d.payload, d.payload_sha256, d.queued_at, d.status,
                   d.attempt_count, d.replay_count, d.next_attempt_at, d.lease_token, d.lease_until,
                   d.delivered_at, d.last_http_status, d.last_error
            FROM ledgerx.webhook_deliveries d
            JOIN ledgerx.webhook_endpoints e ON e.id = d.webhook_endpoint_id
            WHERE d.id = ? AND e.owner_id = ?
            """,
            (resultSet, rowNumber) -> mapDelivery(resultSet),
            deliveryId,
            ownerId)
        .stream()
        .findFirst();
  }

  public List<WebhookDelivery> findAllForEndpointOwner(UUID endpointId, UUID ownerId, int limit) {
    return jdbcTemplate.query(
        """
        SELECT d.id, d.webhook_endpoint_id, d.event_id, d.aggregate_id, d.aggregate_sequence,
               d.event_type, d.schema_version, d.payload, d.payload_sha256, d.queued_at, d.status,
               d.attempt_count, d.replay_count, d.next_attempt_at, d.lease_token, d.lease_until,
               d.delivered_at, d.last_http_status, d.last_error
        FROM ledgerx.webhook_deliveries d
        JOIN ledgerx.webhook_endpoints e ON e.id = d.webhook_endpoint_id
        WHERE d.webhook_endpoint_id = ? AND e.owner_id = ?
        ORDER BY d.queued_at DESC, d.id DESC
        LIMIT ?
        """,
        (resultSet, rowNumber) -> mapDelivery(resultSet),
        endpointId,
        ownerId,
        limit);
  }

  public List<WebhookDeliveryAttempt> findAttempts(UUID deliveryId, int limit, int offset) {
    return jdbcTemplate.query(
        """
        SELECT id, replay_count, attempt_number, started_at, completed_at,
               outcome, http_status, duration_millis, error_category
        FROM ledgerx.webhook_delivery_attempts
        WHERE webhook_delivery_id = ?
        ORDER BY started_at DESC, id DESC
        LIMIT ? OFFSET ?
        """,
        (resultSet, rowNumber) -> {
          int httpStatus = resultSet.getInt("http_status");
          boolean hasHttpStatus = !resultSet.wasNull();
          return new WebhookDeliveryAttempt(
              resultSet.getObject("id", UUID.class),
              resultSet.getInt("replay_count"),
              resultSet.getInt("attempt_number"),
              resultSet.getTimestamp("started_at").toInstant(),
              resultSet.getTimestamp("completed_at").toInstant(),
              WebhookDeliveryOutcome.valueOf(resultSet.getString("outcome")),
              hasHttpStatus ? httpStatus : null,
              resultSet.getLong("duration_millis"),
              resultSet.getString("error_category"));
        },
        deliveryId,
        limit,
        offset);
  }

  public boolean replay(UUID deliveryId) {
    Instant now = clock.instant();
    return jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_deliveries
            SET status = 'PENDING', attempt_count = 0, replay_count = replay_count + 1,
                next_attempt_at = ?, last_http_status = NULL, last_error = NULL
            WHERE id = ? AND status = 'DEAD'
            """,
            Timestamp.from(now),
            deliveryId)
        == 1;
  }

  private void releaseExpiredLeases(Instant now) {
    jdbcTemplate.update(
        """
        UPDATE ledgerx.webhook_deliveries
        SET status = 'PENDING', lease_token = NULL, lease_until = NULL, next_attempt_at = ?,
            last_error = 'delivery lease expired'
        WHERE status = 'IN_FLIGHT' AND lease_until <= ?
        """,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private void appendAttempt(
      WebhookDelivery delivery,
      Instant startedAt,
      Instant completedAt,
      WebhookDeliveryOutcome outcome,
      Integer httpStatus,
      String errorCategory) {
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.webhook_delivery_attempts (
            id, webhook_delivery_id, replay_count, attempt_number, started_at, completed_at,
            outcome, http_status, duration_millis, error_category
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        delivery.id(),
        delivery.replayCount(),
        delivery.attemptCount(),
        Timestamp.from(startedAt),
        Timestamp.from(completedAt),
        outcome.name(),
        httpStatus,
        Math.max(0, Duration.between(startedAt, completedAt).toMillis()),
        errorCategory == null ? null : truncate(errorCategory));
  }

  private WebhookDelivery mapDelivery(java.sql.ResultSet resultSet) throws java.sql.SQLException {
    Timestamp leaseUntil = resultSet.getTimestamp("lease_until");
    Timestamp deliveredAt = resultSet.getTimestamp("delivered_at");
    int lastHttpStatus = resultSet.getInt("last_http_status");
    boolean hasLastHttpStatus = !resultSet.wasNull();
    return new WebhookDelivery(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("webhook_endpoint_id", UUID.class),
        resultSet.getObject("event_id", UUID.class),
        resultSet.getObject("aggregate_id", UUID.class),
        resultSet.getLong("aggregate_sequence"),
        PaymentEventType.fromWireName(resultSet.getString("event_type")),
        resultSet.getInt("schema_version"),
        resultSet.getString("payload"),
        resultSet.getString("payload_sha256"),
        resultSet.getTimestamp("queued_at").toInstant(),
        WebhookDeliveryStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempt_count"),
        resultSet.getInt("replay_count"),
        resultSet.getTimestamp("next_attempt_at").toInstant(),
        resultSet.getObject("lease_token", UUID.class),
        leaseUntil == null ? null : leaseUntil.toInstant(),
        deliveredAt == null ? null : deliveredAt.toInstant(),
        hasLastHttpStatus ? lastHttpStatus : null,
        resultSet.getString("last_error"));
  }

  private void requireLeaseUpdate(int updated) {
    if (updated != 1) {
      throw new IllegalStateException("webhook delivery outcome lost its lease");
    }
  }

  private String truncate(String errorCategory) {
    if (errorCategory == null || errorCategory.isBlank()) {
      return "delivery failed";
    }
    return errorCategory.length() <= 500 ? errorCategory : errorCategory.substring(0, 500);
  }
}
