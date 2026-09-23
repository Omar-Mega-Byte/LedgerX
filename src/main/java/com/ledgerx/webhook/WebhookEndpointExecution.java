package com.ledgerx.webhook;

public record WebhookEndpointExecution(WebhookEndpoint endpoint, boolean replayed) {}
