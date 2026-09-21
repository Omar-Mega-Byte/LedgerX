package com.ledgerx.transfer.domain;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

  @Id private UUID id;

  @Column(name = "source_wallet_account_id", nullable = false, updatable = false)
  private UUID sourceWalletAccountId;

  @Column(name = "destination_wallet_account_id", nullable = false, updatable = false)
  private UUID destinationWalletAccountId;

  @Column(nullable = false, precision = 19, scale = Money.SCALE, updatable = false)
  private java.math.BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private CurrencyCode currency;

  @Column(name = "ledger_transaction_id", nullable = false, updatable = false, unique = true)
  private UUID ledgerTransactionId;

  @Column(name = "completed_at", nullable = false, updatable = false)
  private Instant completedAt;

  protected Transfer() {}

  private Transfer(
      UUID id,
      UUID sourceWalletAccountId,
      UUID destinationWalletAccountId,
      Money money,
      UUID ledgerTransactionId,
      Instant completedAt) {
    this.id = Objects.requireNonNull(id, "transfer id must not be null");
    this.sourceWalletAccountId =
        Objects.requireNonNull(sourceWalletAccountId, "source wallet account id must not be null");
    this.destinationWalletAccountId =
        Objects.requireNonNull(
            destinationWalletAccountId, "destination wallet account id must not be null");
    if (sourceWalletAccountId.equals(destinationWalletAccountId)) {
      throw new TransferValidationException("source and destination wallets must differ");
    }
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new TransferValidationException("transfer amount must be positive");
    }
    this.amount = money.amount();
    this.currency = money.currency();
    this.ledgerTransactionId =
        Objects.requireNonNull(ledgerTransactionId, "ledger transaction id must not be null");
    this.completedAt = Objects.requireNonNull(completedAt, "completed at must not be null");
  }

  public static Transfer completed(
      UUID id,
      UUID sourceWalletAccountId,
      UUID destinationWalletAccountId,
      Money money,
      UUID ledgerTransactionId,
      Instant completedAt) {
    return new Transfer(
        id,
        sourceWalletAccountId,
        destinationWalletAccountId,
        money,
        ledgerTransactionId,
        completedAt);
  }

  public UUID id() {
    return id;
  }

  public UUID sourceWalletAccountId() {
    return sourceWalletAccountId;
  }

  public UUID destinationWalletAccountId() {
    return destinationWalletAccountId;
  }

  public Money money() {
    return new Money(amount, currency);
  }

  public UUID ledgerTransactionId() {
    return ledgerTransactionId;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public TransferStatus status() {
    return TransferStatus.COMPLETED;
  }
}
