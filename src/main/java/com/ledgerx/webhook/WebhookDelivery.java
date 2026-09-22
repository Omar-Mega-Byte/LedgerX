package com.ledgerx.webhook;

import com.ledgerx.reliability.PaymentEventType;
import java.time.Instant;
import java.util.UUID;

public record WebhookDelivery(
    UUID id,
    UUID webhookEndpointId,
    UUID eventId,
    UUID aggregateId,
    long aggregateSequence,
    PaymentEventType eventType,
    int schemaVersion,
    String payload,
    String payloadHash,
    Instant queuedAt,
    WebhookDeliveryStatus status,
    int attemptCount,
    int replayCount,
    Instant nextAttemptAt,
    UUID leaseToken,
    Instant leaseUntil,
    Instant deliveredAt,
    Integer lastHttpStatus,
    String lastError) {}
