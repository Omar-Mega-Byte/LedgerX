package com.ledgerx.reliability;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
    UUID id,
    UUID aggregateId,
    long aggregateSequence,
    PaymentEventType eventType,
    String payload,
    Instant occurredAt,
    OutboxStatus status,
    int attemptCount,
    UUID leaseToken) {}
