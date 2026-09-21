package com.ledgerx.ledger.domain;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
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
@Table(name = "ledger_accounts")
public class LedgerAccount {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_kind", nullable = false, updatable = false)
  private AccountKind accountKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_type", nullable = false, updatable = false)
  private AccountType accountType;

  @Column(name = "owner_id", updatable = false)
  private UUID ownerId;

  @Column(name = "system_code", updatable = false)
  private String systemCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private CurrencyCode currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  protected LedgerAccount() {}

  private LedgerAccount(
      AccountKind accountKind,
      AccountType accountType,
      UUID ownerId,
      String systemCode,
      CurrencyCode currency,
      Clock clock) {
    this.id = UUID.randomUUID();
    this.accountKind = accountKind;
    this.accountType = accountType;
    this.ownerId = ownerId;
    this.systemCode = systemCode;
    this.currency = currency;
    this.status = AccountStatus.ACTIVE;
    this.createdAt = clock.instant();
  }

  public static LedgerAccount wallet(UUID ownerId, CurrencyCode currency, Clock clock) {
    return new LedgerAccount(
        AccountKind.WALLET,
        AccountType.LIABILITY,
        Objects.requireNonNull(ownerId, "owner id must not be null"),
        null,
        Objects.requireNonNull(currency, "currency must not be null"),
        Objects.requireNonNull(clock, "clock must not be null"));
  }

  public static LedgerAccount system(
      AccountType accountType, String systemCode, CurrencyCode currency, Clock clock) {
    if (systemCode == null || systemCode.isBlank()) {
      throw new IllegalArgumentException("system code must not be blank");
    }
    return new LedgerAccount(
        AccountKind.SYSTEM,
        Objects.requireNonNull(accountType, "account type must not be null"),
        null,
        systemCode,
        Objects.requireNonNull(currency, "currency must not be null"),
        Objects.requireNonNull(clock, "clock must not be null"));
  }

  public UUID id() {
    return id;
  }

  public AccountKind accountKind() {
    return accountKind;
  }

  public AccountType accountType() {
    return accountType;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public String systemCode() {
    return systemCode;
  }

  public CurrencyCode currency() {
    return currency;
  }

  public AccountStatus status() {
    return status;
  }

  public boolean isWallet() {
    return accountKind == AccountKind.WALLET;
  }

  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }

  public EntrySide normalSide() {
    return accountType.normalSide();
  }

  public void suspend() {
    if (status == AccountStatus.CLOSED) {
      throw new IllegalStateException("closed accounts cannot be suspended");
    }
    status = AccountStatus.SUSPENDED;
  }

  public void close(Money balance, Clock clock) {
    Objects.requireNonNull(balance, "balance must not be null");
    if (balance.currency() != currency) {
      throw new IllegalArgumentException("balance currency must match account currency");
    }
    if (balance.amount().signum() != 0) {
      throw new IllegalStateException("only zero-balance accounts can close");
    }
    status = AccountStatus.CLOSED;
    closedAt = Objects.requireNonNull(clock, "clock must not be null").instant();
  }
}
