package com.ledgerx.payment.domain;

import com.ledgerx.ledger.domain.FinancialValidationException;

public class PaymentValidationException extends FinancialValidationException {

  public PaymentValidationException(String message) {
    super(message);
  }
}
