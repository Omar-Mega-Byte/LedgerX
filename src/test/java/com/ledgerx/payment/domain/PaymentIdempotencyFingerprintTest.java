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

  @Test
  void persistedPaymentAndRefundFingerprintFormatsRemainStable() {
    PaymentCommand payment =
        new PaymentCommand(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            new Money(new BigDecimal("10.00"), CurrencyCode.USD),
            "payment-key");
    RefundCommand refund =
        new RefundCommand(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            new Money(new BigDecimal("5.00"), CurrencyCode.USD),
            "refund-key");

    assertThat(PaymentIdempotencyFingerprint.forPayment(payment))
        .isEqualTo("4680e447bed9a8d25483ae57ef9daa1ed769fee6e8fd136ec1f437e2be85cd4e");
    assertThat(PaymentIdempotencyFingerprint.forRefund(refund))
        .isEqualTo("8b08ee95c4e2ddd86a4a8d06ec1ff1e58f90c16b0004ee3d29cc5999bb0d988d");
  }
}
