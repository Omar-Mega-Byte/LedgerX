package com.ledgerx.transfer.persistence;

import com.ledgerx.transfer.domain.IdempotencyState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-specific persistence for conflict-safe transfer idempotency claims. */
@Repository
public class TransferIdempotencyStore {

  private final JdbcTemplate jdbcTemplate;

  public TransferIdempotencyStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean claim(
      UUID id, UUID ownerId, String idempotencyKey, String fingerprint, Instant createdAt) {
    return jdbcTemplate.update(
            """
            INSERT INTO ledgerx.transfer_idempotency (
                id, owner_id, idempotency_key, request_fingerprint, state, transfer_id, created_at,
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

  public Optional<TransferIdempotencyRecord> find(UUID ownerId, String idempotencyKey) {
    return jdbcTemplate
        .query(
            """
            SELECT id, owner_id, idempotency_key, request_fingerprint, state, transfer_id, created_at,
                   completed_at
            FROM ledgerx.transfer_idempotency
            WHERE owner_id = ? AND idempotency_key = ?
            """,
            (resultSet, rowNumber) ->
                new TransferIdempotencyRecord(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("owner_id", UUID.class),
                    resultSet.getString("idempotency_key"),
                    resultSet.getString("request_fingerprint"),
                    IdempotencyState.valueOf(resultSet.getString("state")),
                    resultSet.getObject("transfer_id", UUID.class),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("completed_at") == null
                        ? null
                        : resultSet.getTimestamp("completed_at").toInstant()),
            ownerId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public void complete(UUID id, UUID transferId, Instant completedAt) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE ledgerx.transfer_idempotency
            SET state = 'COMPLETED', transfer_id = ?, completed_at = ?
            WHERE id = ? AND state = 'PROCESSING'
            """,
            transferId,
            Timestamp.from(completedAt),
            id);
    if (updated != 1) {
      throw new IllegalStateException(
          "transfer idempotency completion did not update exactly one record");
    }
  }
}
