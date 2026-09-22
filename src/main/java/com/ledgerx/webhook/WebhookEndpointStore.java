package com.ledgerx.webhook;

import com.ledgerx.reliability.PaymentEventType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence for merchant webhook configuration and its creation idempotency contract. */
@Repository
public class WebhookEndpointStore {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public WebhookEndpointStore(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  public Optional<WebhookEndpoint> findById(UUID endpointId) {
    return jdbcTemplate
        .query(
            """
            SELECT id, owner_id, target_url, event_types, secret_ciphertext, secret_key_version, status,
                   created_at, updated_at, disabled_at
            FROM ledgerx.webhook_endpoints
            WHERE id = ?
            """,
            this::mapEndpoint,
            endpointId)
        .stream()
        .findFirst();
  }

  public List<WebhookEndpoint> findAllForOwner(UUID ownerId) {
    return jdbcTemplate.query(
        """
        SELECT id, owner_id, target_url, event_types, secret_ciphertext, secret_key_version, status,
               created_at, updated_at, disabled_at
        FROM ledgerx.webhook_endpoints
        WHERE owner_id = ?
        ORDER BY created_at DESC, id DESC
        """,
        this::mapEndpoint,
        ownerId);
  }

  public List<WebhookEndpoint> findActiveForOwnerAndEvent(
      UUID ownerId, PaymentEventType eventType) {
    return jdbcTemplate.query(
        """
        SELECT id, owner_id, target_url, event_types, secret_ciphertext, secret_key_version, status,
               created_at, updated_at, disabled_at
        FROM ledgerx.webhook_endpoints
        WHERE owner_id = ? AND status = 'ACTIVE' AND event_types LIKE ?
        ORDER BY created_at, id
        """,
        this::mapEndpoint,
        ownerId,
        "%|" + eventType.wireName() + "|%");
  }

  public int countActiveForOwner(UUID ownerId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ledgerx.webhook_endpoints
            WHERE owner_id = ? AND status = 'ACTIVE'
            """,
            Integer.class,
            ownerId);
    return count == null ? 0 : count;
  }

  public void insert(WebhookEndpoint endpoint) {
    jdbcTemplate.update(
        """
        INSERT INTO ledgerx.webhook_endpoints (
            id, owner_id, target_url, event_types, secret_ciphertext, secret_key_version, status,
            created_at, updated_at, disabled_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        endpoint.id(),
        endpoint.ownerId(),
        endpoint.targetUrl(),
        WebhookEndpoint.encodeEventTypes(endpoint.eventTypes()),
        endpoint.secretCiphertext(),
        endpoint.secretKeyVersion(),
        endpoint.status().name(),
        Timestamp.from(endpoint.createdAt()),
        Timestamp.from(endpoint.updatedAt()),
        endpoint.disabledAt() == null ? null : Timestamp.from(endpoint.disabledAt()));
  }

  @Transactional
  public boolean disable(UUID endpointId) {
    Instant now = clock.instant();
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_endpoints
            SET status = 'DISABLED', disabled_at = ?, updated_at = ?
            WHERE id = ? AND status = 'ACTIVE'
            """,
            Timestamp.from(now),
            Timestamp.from(now),
            endpointId);
    if (updated == 1) {
      jdbcTemplate.update(
          """
          UPDATE ledgerx.webhook_deliveries
          SET status = 'CANCELLED', last_error = 'endpoint disabled'
          WHERE webhook_endpoint_id = ? AND status = 'PENDING'
          """,
          endpointId);
    }
    return updated == 1;
  }

  public boolean rotateSecret(UUID endpointId, byte[] ciphertext, int keyVersion) {
    Instant now = clock.instant();
    return jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_endpoints
            SET secret_ciphertext = ?, secret_key_version = ?, updated_at = ?
            WHERE id = ? AND status = 'ACTIVE'
            """,
            ciphertext,
            keyVersion,
            Timestamp.from(now),
            endpointId)
        == 1;
  }

  public EndpointClaim claimCreation(UUID ownerId, String idempotencyKey, String fingerprint) {
    Instant now = clock.instant();
    UUID recordId = UUID.randomUUID();
    int inserted =
        jdbcTemplate.update(
            """
            INSERT INTO ledgerx.webhook_endpoint_idempotency (
                id, owner_id, idempotency_key, request_fingerprint, state, webhook_endpoint_id,
                created_at, completed_at
            )
            VALUES (?, ?, ?, ?, 'PROCESSING', NULL, ?, NULL)
            ON CONFLICT (owner_id, idempotency_key) DO NOTHING
            """,
            recordId,
            ownerId,
            idempotencyKey,
            fingerprint,
            Timestamp.from(now));
    if (inserted == 1) {
      return EndpointClaim.claimed(recordId);
    }

    ExistingIdempotency existing =
        jdbcTemplate.queryForObject(
            """
            SELECT id, request_fingerprint, state, webhook_endpoint_id
            FROM ledgerx.webhook_endpoint_idempotency
            WHERE owner_id = ? AND idempotency_key = ?
            """,
            (resultSet, rowNumber) ->
                new ExistingIdempotency(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("request_fingerprint"),
                    resultSet.getString("state"),
                    resultSet.getObject("webhook_endpoint_id", UUID.class)),
            ownerId,
            idempotencyKey);
    if (!fingerprint.equals(existing.fingerprint())) {
      throw new WebhookIdempotencyKeyReuseException();
    }
    if (!"COMPLETED".equals(existing.state()) || existing.endpointId() == null) {
      throw new WebhookIdempotencyRequestInProgressException();
    }
    WebhookEndpoint endpoint =
        findById(existing.endpointId())
            .orElseThrow(
                () -> new IllegalStateException("webhook idempotency endpoint is missing"));
    return EndpointClaim.replayed(existing.id(), endpoint);
  }

  public void completeCreation(UUID idempotencyId, UUID endpointId) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.webhook_endpoint_idempotency
            SET state = 'COMPLETED', webhook_endpoint_id = ?, completed_at = ?
            WHERE id = ? AND state = 'PROCESSING'
            """,
            endpointId,
            Timestamp.from(clock.instant()),
            idempotencyId);
    if (updated != 1) {
      throw new IllegalStateException("webhook endpoint idempotency completion was lost");
    }
  }

  private WebhookEndpoint mapEndpoint(java.sql.ResultSet resultSet, int rowNumber)
      throws java.sql.SQLException {
    Timestamp disabledAt = resultSet.getTimestamp("disabled_at");
    return new WebhookEndpoint(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("owner_id", UUID.class),
        resultSet.getString("target_url"),
        WebhookEndpoint.decodeEventTypes(resultSet.getString("event_types")),
        resultSet.getBytes("secret_ciphertext"),
        resultSet.getInt("secret_key_version"),
        WebhookEndpointStatus.valueOf(resultSet.getString("status")),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        disabledAt == null ? null : disabledAt.toInstant());
  }

  public record EndpointClaim(UUID idempotencyId, WebhookEndpoint replayedEndpoint) {

    static EndpointClaim claimed(UUID idempotencyId) {
      return new EndpointClaim(idempotencyId, null);
    }

    static EndpointClaim replayed(UUID idempotencyId, WebhookEndpoint endpoint) {
      return new EndpointClaim(idempotencyId, endpoint);
    }

    public boolean isReplayed() {
      return replayedEndpoint != null;
    }
  }

  private record ExistingIdempotency(UUID id, String fingerprint, String state, UUID endpointId) {}
}
