package com.ledgerx.transfer.api;

import com.ledgerx.transfer.domain.Transfer;
import com.ledgerx.transfer.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A completed transfer and the immutable ledger journal it created.")
public record TransferResponse(
    @Schema(format = "uuid", example = "33333333-3333-3333-3333-333333333333") UUID transferId,
    @Schema(format = "uuid", example = "11111111-1111-1111-1111-111111111111") UUID sourceWalletId,
    @Schema(format = "uuid", example = "22222222-2222-2222-2222-222222222222")
        UUID destinationWalletId,
    @Schema(description = "Money moved by this completed transfer.") MoneyResponse money,
    @Schema(description = "A persisted transfer is always terminal.", example = "COMPLETED")
        TransferStatus status,
    @Schema(
            description =
                "UUID of the balanced immutable ledger journal created for this transfer.",
            format = "uuid",
            example = "44444444-4444-4444-4444-444444444444")
        UUID ledgerTransactionId,
    @Schema(
            description = "UTC completion timestamp.",
            type = "string",
            format = "date-time",
            example = "2030-01-02T03:04:05Z")
        Instant completedAt) {

  public static TransferResponse from(Transfer transfer) {
    return new TransferResponse(
        transfer.id(),
        transfer.sourceWalletAccountId(),
        transfer.destinationWalletAccountId(),
        MoneyResponse.from(transfer.money()),
        transfer.status(),
        transfer.ledgerTransactionId(),
        transfer.completedAt());
  }
}
