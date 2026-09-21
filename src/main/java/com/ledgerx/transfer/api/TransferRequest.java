package com.ledgerx.transfer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TransferRequest(
    @NotNull UUID sourceWalletId,
    @NotNull UUID destinationWalletId,
    @NotNull @Valid MoneyRequest money) {}
