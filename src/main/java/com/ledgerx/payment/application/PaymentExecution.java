package com.ledgerx.payment.application;

import com.ledgerx.payment.domain.Payment;
import com.ledgerx.risk.RiskResult;

public record PaymentExecution(Payment payment, boolean replayed, RiskResult risk) {

  public PaymentExecution {
    if ((payment == null) == (risk == null)) {
      throw new IllegalArgumentException("exactly one payment or risk result is required");
    }
  }

  public static PaymentExecution created(Payment payment) {
    return new PaymentExecution(payment, false, null);
  }

  public static PaymentExecution replayed(Payment payment) {
    return new PaymentExecution(payment, true, null);
  }

  public static PaymentExecution risk(RiskResult risk) {
    return new PaymentExecution(null, false, risk);
  }
}
