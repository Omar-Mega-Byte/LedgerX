package com.ledgerx.transfer.api;

import com.ledgerx.money.CurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MoneyRequest(@NotBlank String amount, @NotNull CurrencyCode currency) {}
