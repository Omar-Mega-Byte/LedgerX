package com.ledgerx.payment.domain;

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

/** Immutable business fact for a synchronous payer-to-merchant payment. */
@Entity
@Table(name = "payments")
public class Payment {

  @Id private UUID id;

  @Column(name = "payer_wallet_account_id", nullable = false, updatable = false)
  private UUID payerWalletAccountId;

  @Column(name = "merchant_wallet_account_id", nullable = false, updatable = false)
  private UUID merchantWalletAccountId;

  @Column(nullable = false, precision = 19, scale = Money.SCALE, updatable = false)
  private java.math.BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private CurrencyCode currency;

  @Column(name = "ledger_transaction_id", nullable = false, updatable = false, unique = true)
  private UUID ledgerTransactionId;

  @Column(name = "completed_at", nullable = false, updatable = false)
  private Instant completedAt;

  protected Payment() {}

  private Payment(
      UUID id,
      UUID payerWalletAccountId,
      UUID merchantWalletAccountId,
      Money money,
      UUID ledgerTransactionId,
      Instant completedAt) {
    this.id = Objects.requireNonNull(id, "payment id must not be null");
    this.payerWalletAccountId =
        Objects.requireNonNull(payerWalletAccountId, "payer wallet account id must not be null");
    this.merchantWalletAccountId =
        Objects.requireNonNull(
            merchantWalletAccountId, "merchant wallet account id must not be null");
    if (payerWalletAccountId.equals(merchantWalletAccountId)) {
      throw new PaymentValidationException("payer and merchant wallets must differ");
    }
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new PaymentValidationException("payment amount must be positive");
    }
    this.amount = money.amount();
    this.currency = money.currency();
    this.ledgerTransactionId =
        Objects.requireNonNull(ledgerTransactionId, "ledger transaction id must not be null");
    this.completedAt = Objects.requireNonNull(completedAt, "completed at must not be null");
  }

  public static Payment completed(
      UUID id,
      UUID payerWalletAccountId,
      UUID merchantWalletAccountId,
      Money money,
      UUID ledgerTransactionId,
      Instant completedAt) {
    return new Payment(
        id, payerWalletAccountId, merchantWalletAccountId, money, ledgerTransactionId, completedAt);
  }

  public UUID id() {
    return id;
  }

  public UUID payerWalletAccountId() {
    return payerWalletAccountId;
  }

  public UUID merchantWalletAccountId() {
    return merchantWalletAccountId;
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
}
