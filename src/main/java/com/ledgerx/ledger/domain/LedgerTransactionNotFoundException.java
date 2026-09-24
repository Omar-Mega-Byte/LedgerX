package com.ledgerx.ledger.domain;

public class LedgerTransactionNotFoundException extends RuntimeException {
  public LedgerTransactionNotFoundException() {
    super("ledger transaction was not found");
  }
}
