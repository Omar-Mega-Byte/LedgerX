package com.ledgerx.risk.api;

import com.ledgerx.risk.RiskResult;
import java.time.Instant;
import java.util.UUID;

/** Safe payer-visible risk outcome; rule thresholds and operator notes stay private. */
public record RiskResponse(
    UUID decisionId,
    UUID caseId,
    String outcome,
    String caseStatus,
    Instant expiresAt,
    String code,
    String message) {
  public static RiskResponse from(RiskResult result) {
    String code =
        result.outcome().equals("BLOCK")
            ? "RISK_BLOCKED"
            : result.caseStatus() != null
                    && (result.caseStatus().equals("DECLINED")
                        || result.caseStatus().equals("EXPIRED"))
                ? "RISK_REVIEW_CLOSED"
                : "REVIEW_REQUIRED";
    return new RiskResponse(
        result.decisionId(),
        result.caseId(),
        result.outcome(),
        result.caseStatus(),
        result.expiresAt(),
        code,
        code.equals("RISK_BLOCKED")
            ? "payment blocked by risk policy; no money moved"
            : code.equals("RISK_REVIEW_CLOSED")
                ? "payment review is closed; no money moved"
                : "payment requires review; no money moved");
  }
}
