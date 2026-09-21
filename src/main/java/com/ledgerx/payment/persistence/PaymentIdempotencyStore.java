package com.ledgerx.payment.persistence;

import com.ledgerx.payment.domain.PaymentIdempotencyState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed, payer-scoped idempotency claims for payment commands. */
@Repository
public class PaymentIdempotencyStore {

  private final JdbcTemplate jdbcTemplate;

  public PaymentIdempotencyStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean claim(
      UUID id, UUID ownerId, String idempotencyKey, String fingerprint, Instant createdAt) {
    return jdbcTemplate.update(
            """
            INSERT INTO ledgerx.payment_idempotency (
                id, owner_id, idempotency_key, request_fingerprint, state, payment_id, created_at,
                completed_at
            )
            VALUES (?, ?, ?, ?, 'PROCESSING', NULL, ?, NULL)
            ON CONFLICT (owner_id, idempotency_key) DO NOTHING
            """,
            id,
            ownerId,
            idempotencyKey,
            fingerprint,
            Timestamp.from(createdAt))
        == 1;
  }

  public Optional<PaymentIdempotencyRecord> find(UUID ownerId, String idempotencyKey) {
    return jdbcTemplate
        .query(
            """
            SELECT id, owner_id, idempotency_key, request_fingerprint, state, payment_id, created_at,
                   completed_at
            FROM ledgerx.payment_idempotency
            WHERE owner_id = ? AND idempotency_key = ?
            """,
            (resultSet, rowNumber) ->
                new PaymentIdempotencyRecord(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getString("idempotency_key"),
                    resultSet.getString("request_fingerprint"),
                    PaymentIdempotencyState.valueOf(resultSet.getString("state")),
                    resultSet.getObject("payment_id", UUID.class),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("completed_at") == null
                        ? null
                        : resultSet.getTimestamp("completed_at").toInstant()),
            ownerId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public void complete(UUID id, UUID paymentId, Instant completedAt) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.payment_idempotency
            SET state = 'COMPLETED', payment_id = ?, completed_at = ?
            WHERE id = ? AND state = 'PROCESSING'
            """,
            paymentId,
            Timestamp.from(completedAt),
            id);
    if (updated != 1) {
      throw new IllegalStateException(
          "payment idempotency completion did not update exactly one record");
    }
  }
}
