package com.ledgerx.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** An immutable, non-negative monetary value with LedgerX's supported currency. */
public record Money(BigDecimal amount, CurrencyCode currency) {

  public static final int SCALE = 2;

  public Money {
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");

    try {
      amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          "amount must be representable with " + SCALE + " decimal places", exception);
    }
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("amount must not be negative");
    }
  }

  public static Money zero(CurrencyCode currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other money must not be null");
    if (currency != other.currency) {
      throw new IllegalArgumentException("currencies must match");
    }
  }
}
