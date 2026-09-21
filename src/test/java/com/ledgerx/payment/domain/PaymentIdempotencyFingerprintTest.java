package com.ledgerx.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentIdempotencyFingerprintTest {

  @Test
  void canonicalizesRepresentablePaymentAmounts() {
    UUID payerWalletId = UUID.randomUUID();
    UUID merchantWalletId = UUID.randomUUID();

    String first =
        PaymentIdempotencyFingerprint.forPayment(
            new PaymentCommand(
                payerWalletId,
                merchantWalletId,
                new Money(new BigDecimal("10"), CurrencyCode.USD),
                "payment-key"));
    String second =
        PaymentIdempotencyFingerprint.forPayment(
            new PaymentCommand(
                payerWalletId,
                merchantWalletId,
                new Money(new BigDecimal("10.00"), CurrencyCode.USD),
                "payment-key"));

    assertThat(first).isEqualTo(second).hasSize(64);
  }

  @Test
  void distinguishesPaymentAndRefundInstructions() {
    UUID paymentId = UUID.randomUUID();
    String first =
        PaymentIdempotencyFingerprint.forRefund(
            new RefundCommand(
                paymentId, new Money(new BigDecimal("5.00"), CurrencyCode.USD), "refund-key"));
    String second =
        PaymentIdempotencyFingerprint.forRefund(
            new RefundCommand(
                paymentId, new Money(new BigDecimal("6.00"), CurrencyCode.USD), "refund-key"));

    assertThat(first).isNotEqualTo(second);
  }
}
