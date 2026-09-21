package com.ledgerx.ledger.domain;

public class AccountUnavailableException extends FinancialValidationException {

  public AccountUnavailableException(String message) {
    super(message);
  }
}
