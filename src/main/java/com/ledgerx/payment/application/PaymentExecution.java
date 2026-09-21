package com.ledgerx.payment.application;

import com.ledgerx.payment.domain.Payment;
import java.util.Objects;

public record PaymentExecution(Payment payment, boolean replayed) {

  public PaymentExecution {
    Objects.requireNonNull(payment, "payment must not be null");
  }

  public static PaymentExecution created(Payment payment) {
    return new PaymentExecution(payment, false);
  }

  public static PaymentExecution replayed(Payment payment) {
    return new PaymentExecution(payment, true);
  }
}
