package com.ledgerx.webhook.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record WebhookEndpointRequest(
    @Schema(example = "https://merchant.example.com/ledgerx/webhooks") @NotBlank @Size(max = 2048)
        String url,
    @NotEmpty Set<@NotBlank String> eventTypes,
    @Schema(
            accessMode = Schema.AccessMode.WRITE_ONLY,
            description = "At least 32-character HMAC secret.")
        @NotBlank
        @Size(min = 32, max = 4096)
        String signingSecret) {}
