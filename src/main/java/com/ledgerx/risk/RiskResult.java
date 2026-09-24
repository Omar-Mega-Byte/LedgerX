package com.ledgerx.risk;

import java.time.Instant;
import java.util.UUID;

/** Durable non-payment result of a merchant payment request. */
public record RiskResult(
    UUID decisionId, UUID caseId, String outcome, String caseStatus, Instant expiresAt) {}
