package com.ledgerx.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

  @Test
  void derivesTheRefundLifecycleFromImmutableAmounts() {
    Money payment = money("20.00");

    assertThat(PaymentStatus.from(payment, money("0.00"))).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(PaymentStatus.from(payment, money("1.00")))
        .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(PaymentStatus.from(payment, money("20.00"))).isEqualTo(PaymentStatus.REFUNDED);
  }

  @Test
  void rejectsARefundTotalAboveTheOriginalPayment() {
    assertThatThrownBy(() -> PaymentStatus.from(money("20.00"), money("20.01")))
        .isInstanceOf(PaymentValidationException.class)
        .hasMessageContaining("must not exceed");
  }

  private Money money(String amount) {
    return new Money(new BigDecimal(amount), CurrencyCode.USD);
  }
}
