package com.ledgerx.transfer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of the canonical financial request, scoped separately by the caller owner. */
public final class IdempotencyFingerprint {

  private IdempotencyFingerprint() {}

  public static String forCommand(TransferCommand command) {
    String canonicalRequest =
        String.join(
            "|",
            command.sourceWalletAccountId().toString(),
            command.destinationWalletAccountId().toString(),
            command.money().amount().toPlainString(),
            command.money().currency().name());
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
