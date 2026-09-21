package com.ledgerx.payment.domain;

public class PaymentIdempotencyRequestInProgressException extends RuntimeException {

  public PaymentIdempotencyRequestInProgressException(String message) {
    super(message);
  }
}
