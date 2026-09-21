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

/** Immutable compensating financial fact for one completed payment. */
@Entity
@Table(name = "refunds")
public class Refund {

  @Id private UUID id;

  @Column(name = "payment_id", nullable = false, updatable = false)
  private UUID paymentId;

  @Column(name = "merchant_wallet_account_id", nullable = false, updatable = false)
  private UUID merchantWalletAccountId;

  @Column(name = "payer_wallet_account_id", nullable = false, updatable = false)
  private UUID payerWalletAccountId;

  @Column(nullable = false, precision = 19, scale = Money.SCALE, updatable = false)
  private java.math.BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private CurrencyCode currency;

  @Column(name = "ledger_transaction_id", nullable = false, updatable = false, unique = true)
  private UUID ledgerTransactionId;

  @Column(name = "completed_at", nullable = false, updatable = false)
  private Instant completedAt;

  protected Refund() {}

  private Refund(
      UUID id,
      UUID paymentId,
      UUID merchantWalletAccountId,
      UUID payerWalletAccountId,
      Money money,
      UUID ledgerTransactionId,
      Instant completedAt) {
    this.id = Objects.requireNonNull(id, "refund id must not be null");
    this.paymentId = Objects.requireNonNull(paymentId, "payment id must not be null");
    this.merchantWalletAccountId =
        Objects.requireNonNull(
            merchantWalletAccountId, "merchant wallet account id must not be null");
    this.payerWalletAccountId =
        Objects.requireNonNull(payerWalletAccountId, "payer wallet account id must not be null");
    if (merchantWalletAccountId.equals(payerWalletAccountId)) {
      throw new PaymentValidationException("merchant and payer wallets must differ");
    }
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new PaymentValidationException("refund amount must be positive");
    }
    this.amount = money.amount();
    this.currency = money.currency();
    this.ledgerTransactionId =
        Objects.requireNonNull(ledgerTransactionId, "ledger transaction id must not be null");
    this.completedAt = Objects.requireNonNull(completedAt, "completed at must not be null");
  }

  public static Refund completed(
      UUID id,
      UUID paymentId,
      UUID merchantWalletAccountId,
      UUID payerWalletAccountId,
      Money money,
      UUID ledgerTransactionId,
      Instant completedAt) {
    return new Refund(
        id,
        paymentId,
        merchantWalletAccountId,
        payerWalletAccountId,
        money,
        ledgerTransactionId,
        completedAt);
  }

  public UUID id() {
    return id;
  }

  public UUID paymentId() {
    return paymentId;
  }

  public UUID merchantWalletAccountId() {
    return merchantWalletAccountId;
  }

  public UUID payerWalletAccountId() {
    return payerWalletAccountId;
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
