package com.ledgerx.reliability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Versioned JSON event payload carried by the transactional outbox and Kafka. */
public record PaymentEventEnvelope(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    long aggregateSequence,
    int schemaVersion,
    Instant occurredAt,
    Map<String, Object> data) {

  public PaymentEventEnvelope {
    Objects.requireNonNull(eventId, "event id must not be null");
    PaymentEventType.fromWireName(eventType);
    Objects.requireNonNull(aggregateId, "aggregate id must not be null");
    if (aggregateSequence <= 0) {
      throw new IllegalArgumentException("aggregate sequence must be positive");
    }
    if (schemaVersion != 1) {
      throw new IllegalArgumentException("only payment event schema version 1 is supported");
    }
    Objects.requireNonNull(occurredAt, "occurred at must not be null");
    data = Map.copyOf(Objects.requireNonNull(data, "event data must not be null"));
  }
}
