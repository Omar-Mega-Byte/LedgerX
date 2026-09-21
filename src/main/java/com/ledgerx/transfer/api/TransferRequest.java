package com.ledgerx.transfer.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "The immutable financial instruction for one wallet-to-wallet transfer.")
public record TransferRequest(
    @Schema(
            description =
                "UUID of the active USD wallet to debit. It must belong to X-LedgerX-Owner-Id.",
            format = "uuid",
            example = "11111111-1111-1111-1111-111111111111")
        @NotNull
        UUID sourceWalletId,
    @Schema(
            description = "UUID of a different active USD wallet to credit.",
            format = "uuid",
            example = "22222222-2222-2222-2222-222222222222")
        @NotNull
        UUID destinationWalletId,
    @Schema(description = "Money to move from source to destination.") @NotNull @Valid
        MoneyRequest money) {}
