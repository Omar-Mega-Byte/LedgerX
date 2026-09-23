package com.ledgerx.webhook;

import java.time.Duration;

public record WebhookHttpResponse(int statusCode, Duration retryAfter) {}
