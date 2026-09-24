package com.ledgerx.risk.api;

import com.ledgerx.risk.PaymentRiskService;
import com.ledgerx.risk.RiskValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Role-gated policy and review controls. No method can initiate a payment. */
@Validated
@RestController
@RequestMapping("/api/v1/operations/risk")
@Tag(name = "Operator Risk", description = "Role-gated payment risk policy and review actions.")
public class RiskOperationsController {

  private final PaymentRiskService riskService;

  public RiskOperationsController(PaymentRiskService riskService) {
    this.riskService = riskService;
  }

  @GetMapping("/policy")
  public PolicyResponse activePolicy() {
    return PolicyResponse.from(riskService.activePolicy());
  }

  @PostMapping("/policies")
  @Operation(summary = "Activate a versioned payment risk policy")
  public PolicyResponse activate(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String commandKey,
      @Valid @RequestBody PolicyRequest request) {
    return PolicyResponse.from(
        riskService.activate(
            request.enabled(),
            decimal(request.maxPaymentAmount()),
            request.reviewPaymentCount(),
            decimal(request.reviewPaymentTotal()),
            actor(jwt),
            request.reason(),
            commandKey,
            request.expectedVersion()));
  }

  @GetMapping("/cases")
  public List<OperatorCaseResponse> cases(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page) {
    return riskService.listCases(status, limit, page).stream()
        .map(OperatorCaseResponse::from)
        .toList();
  }

  @GetMapping("/cases/{caseId}")
  public OperatorCaseResponse findCase(@PathVariable UUID caseId) {
    return OperatorCaseResponse.from(riskService.operatorCase(caseId));
  }

  @GetMapping("/cases/{caseId}/actions")
  public List<PaymentRiskService.ReviewAction> actions(@PathVariable UUID caseId) {
    return riskService.actions(caseId);
  }

  @PostMapping("/cases/{caseId}/approve")
  @Operation(summary = "Approve one payer retry without posting money")
  public PaymentRiskService.ReviewCase approve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID caseId,
      @Valid @RequestBody ReviewRequest request) {
    return riskService.decide(caseId, "APPROVE", request.reason(), actor(jwt));
  }

  @PostMapping("/cases/{caseId}/decline")
  @Operation(summary = "Decline a payment review case without posting money")
  public PaymentRiskService.ReviewCase decline(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID caseId,
      @Valid @RequestBody ReviewRequest request) {
    return riskService.decide(caseId, "DECLINE", request.reason(), actor(jwt));
  }

  private String actor(Jwt jwt) {
    if (jwt == null
        || jwt.getSubject() == null
        || jwt.getSubject().isBlank()
        || jwt.getSubject().length() > 255) {
      throw new RiskValidationException("operator token has no subject");
    }
    return jwt.getSubject();
  }

  private BigDecimal decimal(String value) {
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException exception) {
      throw new RiskValidationException("risk thresholds must be decimal strings");
    }
  }

  public record PolicyRequest(
      @NotNull Boolean enabled,
      @NotBlank String maxPaymentAmount,
      @Min(1) int reviewPaymentCount,
      @NotBlank String reviewPaymentTotal,
      @NotBlank @Size(max = 500) String reason,
      @Min(1) long expectedVersion) {}

  public record ReviewRequest(@NotBlank @Size(max = 500) String reason) {}

  public record PolicyResponse(
      UUID id,
      long versionNumber,
      boolean enabled,
      String maxPaymentAmount,
      int reviewPaymentCount,
      String reviewPaymentTotal,
      String actorSubject,
      String changeReason,
      Instant createdAt) {
    static PolicyResponse from(PaymentRiskService.Policy policy) {
      return new PolicyResponse(
          policy.id(),
          policy.versionNumber(),
          policy.enabled(),
          policy.maxPaymentAmount().toPlainString(),
          policy.reviewPaymentCount(),
          policy.reviewPaymentTotal().toPlainString(),
          policy.actorSubject(),
          policy.changeReason(),
          policy.createdAt());
    }
  }

  public record OperatorCaseResponse(
      UUID caseId,
      UUID payerOwnerId,
      String status,
      Instant createdAt,
      Instant openExpiresAt,
      Instant approvalExpiresAt,
      UUID paymentId,
      String ruleCodes,
      int completedCount,
      String completedTotal,
      UUID policyId) {
    static OperatorCaseResponse from(PaymentRiskService.OperatorCase review) {
      return new OperatorCaseResponse(
          review.caseId(),
          review.payerOwnerId(),
          review.status(),
          review.createdAt(),
          review.openExpiresAt(),
          review.approvalExpiresAt(),
          review.paymentId(),
          review.ruleCodes(),
          review.completedCount(),
          review.completedTotal().toPlainString(),
          review.policyId());
    }
  }
}
