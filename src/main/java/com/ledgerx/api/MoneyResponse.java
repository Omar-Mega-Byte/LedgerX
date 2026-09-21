package com.ledgerx.api;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A canonical LedgerX monetary value.")
public record MoneyResponse(
    @Schema(description = "Two-decimal canonical amount.", example = "25.00") String amount,
    @Schema(description = "Currency code.", example = "USD") CurrencyCode currency) {

  public static MoneyResponse from(Money money) {
    return new MoneyResponse(money.amount().toPlainString(), money.currency());
  }
}
