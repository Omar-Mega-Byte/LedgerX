package com.ledgerx.payment.domain;

import com.ledgerx.money.Money;
import java.util.Objects;
import java.util.UUID;

public record RefundCommand(UUID paymentId, Money money, String idempotencyKey) {

  public RefundCommand {
    Objects.requireNonNull(paymentId, "payment id must not be null");
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new PaymentValidationException("refund amount must be positive");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new PaymentValidationException("idempotency key must not be blank");
    }
  }
}
