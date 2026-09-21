package com.ledgerx.payment.application;

import com.ledgerx.money.Money;
import com.ledgerx.payment.domain.Payment;
import com.ledgerx.payment.domain.PaymentStatus;
import java.util.Objects;

public record PaymentView(
    Payment payment, PaymentStatus status, Money refundedMoney, Money remainingRefundableMoney) {

  public PaymentView {
    Objects.requireNonNull(payment, "payment must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(refundedMoney, "refunded money must not be null");
    Objects.requireNonNull(remainingRefundableMoney, "remaining refundable money must not be null");
  }
}
