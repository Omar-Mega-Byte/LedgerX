package com.ledgerx.transfer.domain;

public class IdempotencyKeyReuseException extends RuntimeException {

  public IdempotencyKeyReuseException(String message) {
    super(message);
  }
}
