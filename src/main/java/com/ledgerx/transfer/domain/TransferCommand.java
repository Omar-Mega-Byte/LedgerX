package com.ledgerx.transfer.domain;

import com.ledgerx.money.Money;
import java.util.Objects;
import java.util.UUID;

public record TransferCommand(
    UUID sourceWalletAccountId,
    UUID destinationWalletAccountId,
    Money money,
    String idempotencyKey) {

  public TransferCommand {
    Objects.requireNonNull(sourceWalletAccountId, "source wallet account id must not be null");
    Objects.requireNonNull(
        destinationWalletAccountId, "destination wallet account id must not be null");
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new TransferValidationException("transfer amount must be positive");
    }
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
      throw new IllegalArgumentException(
          "idempotency key must contain at most 255 non-blank characters");
    }
  }
}
