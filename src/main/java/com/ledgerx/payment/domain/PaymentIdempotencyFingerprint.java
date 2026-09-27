package com.ledgerx.payment.domain;

import com.ledgerx.crypto.Sha256;

/** SHA-256 fingerprints of canonical payment commands; owner scope is stored separately. */
public final class PaymentIdempotencyFingerprint {

  private PaymentIdempotencyFingerprint() {}

  public static String forPayment(PaymentCommand command) {
    return Sha256.hexUtf8(
        String.join(
            "|",
            command.payerWalletAccountId().toString(),
            command.merchantWalletAccountId().toString(),
            command.money().amount().toPlainString(),
            command.money().currency().name()));
  }

  public static String forRefund(RefundCommand command) {
    return Sha256.hexUtf8(
        String.join(
            "|",
            command.paymentId().toString(),
            command.money().amount().toPlainString(),
            command.money().currency().name()));
  }
}
