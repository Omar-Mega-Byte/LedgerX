package com.ledgerx.reliability;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable per-consumer event receipt used to make duplicate Kafka delivery a no-op. */
@Repository
public class ProcessedEventStore {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public ProcessedEventStore(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Transactional
  public boolean record(String consumerName, PaymentEventEnvelope envelope, String payload) {
    String payloadHash = PayloadHash.sha256(payload);
    int inserted =
        jdbcTemplate.update(
            """
            INSERT INTO ledgerx.processed_events (
                consumer_name, event_id, event_type, aggregate_id, payload_sha256, processed_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """,
            consumerName,
            envelope.eventId(),
            envelope.eventType(),
            envelope.aggregateId(),
            payloadHash,
            Timestamp.from(clock.instant()));
    if (inserted == 1) {
      return true;
    }

    String existingHash =
        jdbcTemplate.queryForObject(
            """
            SELECT payload_sha256
            FROM ledgerx.processed_events
            WHERE consumer_name = ? AND event_id = ?
            """,
            String.class,
            consumerName,
            envelope.eventId());
    if (!payloadHash.equals(existingHash)) {
      throw new IllegalStateException("event id was redelivered with a different payload");
    }
    return false;
  }
}
