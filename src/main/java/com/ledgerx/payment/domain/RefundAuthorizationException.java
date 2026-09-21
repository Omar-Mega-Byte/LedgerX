package com.ledgerx.payment.domain;

public class RefundAuthorizationException extends RuntimeException {

  public RefundAuthorizationException(String message) {
    super(message);
  }
}
