package com.ledgerx.payment.persistence;

import com.ledgerx.payment.domain.PaymentIdempotencyState;
import java.time.Instant;
import java.util.UUID;

public record PaymentIdempotencyRecord(
    UUID id,
    UUID ownerId,
    String idempotencyKey,
    String requestFingerprint,
    PaymentIdempotencyState state,
    UUID resultId,
    Instant createdAt,
    Instant completedAt) {}
