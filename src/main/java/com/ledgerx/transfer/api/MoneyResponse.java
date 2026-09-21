package com.ledgerx.transfer.api;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;

public record MoneyResponse(String amount, CurrencyCode currency) {

  public static MoneyResponse from(Money money) {
    return new MoneyResponse(money.amount().toPlainString(), money.currency());
  }
}
