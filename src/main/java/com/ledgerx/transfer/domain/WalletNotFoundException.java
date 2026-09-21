package com.ledgerx.transfer.domain;

public class WalletNotFoundException extends RuntimeException {

  public WalletNotFoundException(String message) {
    super(message);
  }
}
