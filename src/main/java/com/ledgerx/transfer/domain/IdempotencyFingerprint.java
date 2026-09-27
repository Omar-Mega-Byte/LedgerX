package com.ledgerx.transfer.domain;

import com.ledgerx.crypto.Sha256;

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
    return Sha256.hexUtf8(canonicalRequest);
  }
}
