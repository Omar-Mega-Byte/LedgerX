package com.ledgerx.transfer.domain;

public class IdempotencyRequestInProgressException extends RuntimeException {

  public IdempotencyRequestInProgressException(String message) {
    super(message);
  }
}
