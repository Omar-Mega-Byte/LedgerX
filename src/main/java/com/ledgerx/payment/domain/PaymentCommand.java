package com.ledgerx.payment.domain;

import com.ledgerx.money.Money;
import java.util.Objects;
import java.util.UUID;

public record PaymentCommand(
    UUID payerWalletAccountId, UUID merchantWalletAccountId, Money money, String idempotencyKey) {

  public PaymentCommand {
    Objects.requireNonNull(payerWalletAccountId, "payer wallet account id must not be null");
    Objects.requireNonNull(merchantWalletAccountId, "merchant wallet account id must not be null");
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new PaymentValidationException("payment amount must be positive");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new PaymentValidationException("idempotency key must not be blank");
    }
  }
}
