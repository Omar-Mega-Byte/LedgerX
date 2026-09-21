package com.ledgerx.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void canonicalizesRepresentableAmountsToUsdScale() {
    Money money = new Money(new BigDecimal("10"), CurrencyCode.USD);

    assertThat(money.amount()).isEqualByComparingTo("10.00");
    assertThat(money.amount().scale()).isEqualTo(2);
  }

  @Test
  void rejectsAmountsThatRequireRounding() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Money(new BigDecimal("10.001"), CurrencyCode.USD));
  }

  @Test
  void rejectsNegativeAmountsButAllowsZeroBalances() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Money(new BigDecimal("-0.01"), CurrencyCode.USD));

    assertThat(Money.zero(CurrencyCode.USD).isPositive()).isFalse();
  }

  @Test
  void subtractRejectsAnAmountGreaterThanTheValue() {
    Money balance = new Money(new BigDecimal("10.00"), CurrencyCode.USD);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> balance.subtract(new Money(new BigDecimal("10.01"), CurrencyCode.USD)));
  }
}
