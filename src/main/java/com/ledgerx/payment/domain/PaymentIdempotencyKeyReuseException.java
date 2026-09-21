package com.ledgerx.payment.domain;

public class PaymentIdempotencyKeyReuseException extends RuntimeException {

  public PaymentIdempotencyKeyReuseException(String message) {
    super(message);
  }
}
