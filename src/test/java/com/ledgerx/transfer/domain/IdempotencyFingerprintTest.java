package com.ledgerx.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyFingerprintTest {

  @Test
  void canonicalMoneyAmountsProduceTheSameFingerprint() {
    UUID sourceWalletId = UUID.randomUUID();
    UUID destinationWalletId = UUID.randomUUID();

    String wholeAmount =
        IdempotencyFingerprint.forCommand(
            command(sourceWalletId, destinationWalletId, new BigDecimal("10")));
    String twoDecimalAmount =
        IdempotencyFingerprint.forCommand(
            command(sourceWalletId, destinationWalletId, new BigDecimal("10.00")));

    assertThat(wholeAmount).isEqualTo(twoDecimalAmount).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void fingerprintChangesWhenTheFinancialRequestChanges() {
    UUID sourceWalletId = UUID.randomUUID();
    UUID destinationWalletId = UUID.randomUUID();

    String original =
        IdempotencyFingerprint.forCommand(
            command(sourceWalletId, destinationWalletId, new BigDecimal("10.00")));
    String changedAmount =
        IdempotencyFingerprint.forCommand(
            command(sourceWalletId, destinationWalletId, new BigDecimal("11.00")));

    assertThat(changedAmount).isNotEqualTo(original);
  }

  @Test
  void persistedTransferFingerprintFormatRemainsStable() {
    TransferCommand command =
        command(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            new BigDecimal("10.00"));

    assertThat(IdempotencyFingerprint.forCommand(command))
        .isEqualTo("4680e447bed9a8d25483ae57ef9daa1ed769fee6e8fd136ec1f437e2be85cd4e");
  }

  @Test
  void transferFactsRejectZeroAmountsAndTheSameWallet() {
    UUID walletId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                new TransferCommand(
                    walletId,
                    UUID.randomUUID(),
                    new Money(BigDecimal.ZERO, CurrencyCode.USD),
                    "key"))
        .isInstanceOf(TransferValidationException.class)
        .hasMessage("transfer amount must be positive");

    assertThatThrownBy(
            () ->
                Transfer.completed(
                    UUID.randomUUID(),
                    walletId,
                    walletId,
                    new Money(new BigDecimal("1.00"), CurrencyCode.USD),
                    UUID.randomUUID(),
                    Instant.now()))
        .isInstanceOf(TransferValidationException.class)
        .hasMessage("source and destination wallets must differ");
  }

  private TransferCommand command(
      UUID sourceWalletId, UUID destinationWalletId, BigDecimal amount) {
    return new TransferCommand(
        sourceWalletId,
        destinationWalletId,
        new Money(amount, CurrencyCode.USD),
        "idempotency-key");
  }
}
