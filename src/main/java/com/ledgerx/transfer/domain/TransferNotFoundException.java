package com.ledgerx.transfer.domain;

public class TransferNotFoundException extends RuntimeException {

  public TransferNotFoundException(String message) {
    super(message);
  }
}
