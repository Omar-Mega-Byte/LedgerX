package com.ledgerx.payment.api;

import com.ledgerx.api.MoneyRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "A payer-authorized instruction to pay an active merchant wallet.")
public record PaymentRequest(
    @Schema(
            description = "UUID of the PERSON wallet to debit. It must belong to the caller.",
            format = "uuid",
            example = "11111111-1111-1111-1111-111111111111")
        @NotNull
        UUID payerWalletId,
    @Schema(
            description = "UUID of the active MERCHANT wallet to credit.",
            format = "uuid",
            example = "22222222-2222-2222-2222-222222222222")
        @NotNull
        UUID merchantWalletId,
    @Schema(description = "Positive exact USD amount.") @NotNull @Valid MoneyRequest money) {}
