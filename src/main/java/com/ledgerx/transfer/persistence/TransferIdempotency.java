package com.ledgerx.transfer.persistence;

import com.ledgerx.transfer.domain.IdempotencyState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_idempotency")
class TransferIdempotency {

  @Id private UUID id;

  @Column(name = "owner_id", nullable = false, updatable = false)
  private UUID ownerId;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
  private String idempotencyKey;

  @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
  private String requestFingerprint;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private IdempotencyState state;

  @Column(name = "transfer_id")
  private UUID transferId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected TransferIdempotency() {}
}
