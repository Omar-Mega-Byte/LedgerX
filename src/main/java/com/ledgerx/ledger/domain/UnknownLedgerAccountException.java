package com.ledgerx.ledger.domain;

public class UnknownLedgerAccountException extends FinancialValidationException {

  public UnknownLedgerAccountException(String message) {
    super(message);
  }
}
