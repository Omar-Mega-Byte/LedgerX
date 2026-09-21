package com.ledgerx.api;

import com.ledgerx.money.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A USD monetary amount supplied by an API caller.")
public record MoneyRequest(
    @Schema(
            description = "Positive decimal amount with at most two decimal places.",
            example = "25.00")
        @NotBlank
        String amount,
    @Schema(description = "LedgerX currently supports USD only.", example = "USD") @NotNull
        CurrencyCode currency) {}
