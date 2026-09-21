package com.ledgerx.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One invalid request field.")
public record ApiFieldError(
    @Schema(example = "money.amount") String field,
    @Schema(example = "must not be blank") String message) {}
