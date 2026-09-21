package com.ledgerx.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wallet_owners")
public class WalletOwner {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_type", nullable = false, updatable = false)
  private OwnerType ownerType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OwnerStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WalletOwner() {}

  private WalletOwner(UUID id, OwnerType ownerType, Instant createdAt) {
    this.id = id;
    this.ownerType = ownerType;
    this.status = OwnerStatus.ACTIVE;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  public static WalletOwner create(OwnerType ownerType, Clock clock) {
    Objects.requireNonNull(ownerType, "owner type must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    return new WalletOwner(UUID.randomUUID(), ownerType, clock.instant());
  }

  public UUID id() {
    return id;
  }

  public OwnerType ownerType() {
    return ownerType;
  }

  public OwnerStatus status() {
    return status;
  }

  public boolean isActive() {
    return status == OwnerStatus.ACTIVE;
  }

  public void suspend(Clock clock) {
    status = OwnerStatus.SUSPENDED;
    updatedAt = Objects.requireNonNull(clock, "clock must not be null").instant();
  }
}
