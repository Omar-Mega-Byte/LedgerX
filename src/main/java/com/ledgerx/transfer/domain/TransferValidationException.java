package com.ledgerx.transfer.domain;

import com.ledgerx.ledger.domain.FinancialValidationException;

public class TransferValidationException extends FinancialValidationException {

  public TransferValidationException(String message) {
    super(message);
  }
}
