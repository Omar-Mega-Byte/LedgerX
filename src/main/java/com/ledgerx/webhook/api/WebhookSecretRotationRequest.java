package com.ledgerx.webhook.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WebhookSecretRotationRequest(
    @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, description = "Replacement HMAC secret.")
        @NotBlank
        @Size(min = 32, max = 4096)
        String signingSecret) {}
