package com.ledgerx.risk.api;

import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.risk.PaymentRiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Only the original payer can inspect a review case. */
@RestController
@RequestMapping("/api/v1/payment-risk-cases")
@Tag(
    name = "Payment Risk Cases",
    description = "Payer-owned review status without rule thresholds.")
public class RiskCaseController {

  private final PaymentRiskService riskService;
  private final OwnerContextResolver ownerContextResolver;

  public RiskCaseController(
      PaymentRiskService riskService, OwnerContextResolver ownerContextResolver) {
    this.riskService = riskService;
    this.ownerContextResolver = ownerContextResolver;
  }

  @GetMapping("/{caseId}")
  @Operation(summary = "Get the original payer's payment review case")
  public RiskResponse find(
      @RequestHeader(value = "X-LedgerX-Owner-Id", required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID caseId) {
    UUID ownerId = ownerContextResolver.resolve(ownerHeader, jwt).ownerId();
    PaymentRiskService.ReviewCase review = riskService.caseForOwner(caseId, ownerId);
    return RiskResponse.from(
        new com.ledgerx.risk.RiskResult(
            review.assessmentId(),
            review.id(),
            "REVIEW",
            review.status(),
            review.approvalExpiresAt() == null
                ? review.openExpiresAt()
                : review.approvalExpiresAt()));
  }
}
