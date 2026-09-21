package com.ledgerx.transfer.persistence;

import com.ledgerx.transfer.domain.IdempotencyState;
import java.time.Instant;
import java.util.UUID;

public record TransferIdempotencyRecord(
    UUID id,
    UUID ownerId,
    String idempotencyKey,
    String requestFingerprint,
    IdempotencyState state,
    UUID transferId,
    Instant createdAt,
    Instant completedAt) {}
