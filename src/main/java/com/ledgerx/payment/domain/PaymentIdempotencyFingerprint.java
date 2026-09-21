package com.ledgerx.payment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 fingerprints of canonical payment commands; owner scope is stored separately. */
public final class PaymentIdempotencyFingerprint {

  private PaymentIdempotencyFingerprint() {}

  public static String forPayment(PaymentCommand command) {
    return hash(
        String.join(
            "|",
            command.payerWalletAccountId().toString(),
            command.merchantWalletAccountId().toString(),
            command.money().amount().toPlainString(),
            command.money().currency().name()));
  }

  public static String forRefund(RefundCommand command) {
    return hash(
        String.join(
            "|",
            command.paymentId().toString(),
            command.money().amount().toPlainString(),
            command.money().currency().name()));
  }

  private static String hash(String canonicalRequest) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }
}
