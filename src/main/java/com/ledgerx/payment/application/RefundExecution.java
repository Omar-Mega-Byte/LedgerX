package com.ledgerx.payment.application;

import com.ledgerx.payment.domain.Refund;
import java.util.Objects;

public record RefundExecution(Refund refund, boolean replayed) {

  public RefundExecution {
    Objects.requireNonNull(refund, "refund must not be null");
  }

  public static RefundExecution created(Refund refund) {
    return new RefundExecution(refund, false);
  }

  public static RefundExecution replayed(Refund refund) {
    return new RefundExecution(refund, true);
  }
}
