package com.ledgerx.webhook;

import java.time.Instant;
import java.util.UUID;

/** Redacted immutable evidence for one outbound attempt. */
public record WebhookDeliveryAttempt(
    UUID id,
    int replayCount,
    int attemptNumber,
    Instant startedAt,
    Instant completedAt,
    WebhookDeliveryOutcome outcome,
    Integer httpStatus,
    long durationMillis,
    String errorCategory) {}
