package com.ledgerx.payment.domain;

public class PaymentAuthorizationException extends RuntimeException {

  public PaymentAuthorizationException(String message) {
    super(message);
  }
}
