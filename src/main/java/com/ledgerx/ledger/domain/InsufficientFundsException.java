package com.ledgerx.ledger.domain;

public class InsufficientFundsException extends FinancialValidationException {

  public InsufficientFundsException(String message) {
    super(message);
  }
}
