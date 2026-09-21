package com.ledgerx.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Stable error envelope returned by the public API.")
public record ApiError(
    @Schema(format = "date-time", example = "2030-01-02T03:04:05Z") Instant timestamp,
    @Schema(example = "422") int status,
    @Schema(example = "PAYMENT_NOT_PROCESSABLE") String code,
    @Schema(example = "wallet account has insufficient funds") String message,
    @Schema(example = "/api/v1/payments") String path,
    @Schema(description = "Field-level validation details. Empty for business errors.")
        List<ApiFieldError> details) {}
