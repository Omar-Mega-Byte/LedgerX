package com.ledgerx.payment.domain;

import com.ledgerx.money.Money;
import java.util.Objects;

/** Lifecycle state calculated from immutable payment and refund facts. */
public enum PaymentStatus {
  COMPLETED,
  PARTIALLY_REFUNDED,
  REFUNDED;

  public static PaymentStatus from(Money paymentAmount, Money refundedAmount) {
    Objects.requireNonNull(paymentAmount, "payment amount must not be null");
    Objects.requireNonNull(refundedAmount, "refunded amount must not be null");
    if (paymentAmount.currency() != refundedAmount.currency()) {
      throw new IllegalArgumentException("payment and refunded currencies must match");
    }
    int comparison = refundedAmount.amount().compareTo(paymentAmount.amount());
    if (comparison > 0) {
      throw new PaymentValidationException("refund total must not exceed the payment amount");
    }
    if (refundedAmount.amount().signum() == 0) {
      return COMPLETED;
    }
    return comparison == 0 ? REFUNDED : PARTIALLY_REFUNDED;
  }
}
