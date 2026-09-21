package com.ledgerx.payment.api;

import com.ledgerx.api.MoneyRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Schema(description = "A merchant-authorized full or partial refund of one completed payment.")
public record RefundRequest(
    @Schema(description = "Positive USD amount not greater than the remaining refundable amount.")
        @NotNull
        @Valid
        MoneyRequest money) {}
