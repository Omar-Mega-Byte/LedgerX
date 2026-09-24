package com.ledgerx.payment.api;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.api.ApiError;
import com.ledgerx.api.MalformedRequestException;
import com.ledgerx.api.MoneyRequest;
import com.ledgerx.money.Money;
import com.ledgerx.payment.application.PaymentApplicationService;
import com.ledgerx.payment.application.PaymentExecution;
import com.ledgerx.payment.application.PaymentQueryService;
import com.ledgerx.payment.application.RefundApplicationService;
import com.ledgerx.payment.application.RefundExecution;
import com.ledgerx.payment.domain.PaymentCommand;
import com.ledgerx.payment.domain.PaymentNotFoundException;
import com.ledgerx.payment.domain.PaymentValidationException;
import com.ledgerx.payment.domain.RefundCommand;
import com.ledgerx.payment.persistence.RefundRepository;
import com.ledgerx.risk.api.RiskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for payments and refunds, with profile-specific caller identity resolution. */
@Validated
@RestController
@RequestMapping("/api/v1/payments")
@Tag(
    name = "Merchant Payments",
    description =
        "Atomic payer-authorized USD payments and merchant-authorized compensating refunds.")
public class PaymentController {

  static final String OWNER_HEADER = "X-LedgerX-Owner-Id";
  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final PaymentApplicationService paymentApplicationService;
  private final RefundApplicationService refundApplicationService;
  private final PaymentQueryService paymentQueryService;
  private final RefundRepository refundRepository;
  private final OwnerContextResolver ownerContextResolver;

  public PaymentController(
      PaymentApplicationService paymentApplicationService,
      RefundApplicationService refundApplicationService,
      PaymentQueryService paymentQueryService,
      RefundRepository refundRepository,
      OwnerContextResolver ownerContextResolver) {
    this.paymentApplicationService = paymentApplicationService;
    this.refundApplicationService = refundApplicationService;
    this.paymentQueryService = paymentQueryService;
    this.refundRepository = refundRepository;
    this.ownerContextResolver = ownerContextResolver;
  }

  @PostMapping
  @Operation(
      operationId = "createPayment",
      summary = "Create or replay a USD merchant payment",
      description =
          """
          Debits a PERSON payer wallet and credits a MERCHANT wallet through one balanced immutable
          journal. The caller must own the payer wallet. Production requires a signed Keycloak JWT;
          the development owner header is available only outside the production profile.

          Repeating the same owner, Idempotency-Key, and canonical request replays its durable
          outcome. An enabled risk policy may return REVIEW or BLOCK without moving money. Only the
          original payer can complete an approved REVIEW with the same request and key.
          """)
  @ApiResponses({
    @ApiResponse(
        responseCode = "202",
        description = "Risk review is required; no money has moved.",
        content = @Content(schema = @Schema(implementation = RiskResponse.class))),
    @ApiResponse(
        responseCode = "201",
        description =
            "A new payment, ledger journal, idempotency result, and outbox event committed atomically.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PaymentResponse.class))),
    @ApiResponse(
        responseCode = "200",
        description =
            "An identical completed request was replayed without another financial effect.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = PaymentResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "403",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "422",
        description = "Financial validation error or a durable blocked/closed risk result.",
        content = @Content(schema = @Schema(oneOf = {ApiError.class, RiskResponse.class}))),
    @ApiResponse(
        responseCode = "500",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<?> create(
      @Parameter(
              name = OWNER_HEADER,
              in = ParameterIn.HEADER,
              required = false,
              example = "11111111-1111-1111-1111-111111111111",
              description =
                  "Required only for local/test use. It is a forgeable development owner UUID, not authentication. In production, a Keycloak-signed ledgerx_owner_id claim is required instead.")
          @RequestHeader(value = OWNER_HEADER, required = false)
          String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @Parameter(
              name = IDEMPOTENCY_HEADER,
              in = ParameterIn.HEADER,
              required = true,
              example = "payment-demo-0001",
              description =
                  "Client-supplied key scoped to this owner. Reuse only to replay the same command.")
          @RequestHeader(IDEMPOTENCY_HEADER)
          @NotBlank
          @Size(max = 255)
          String idempotencyKey,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = PaymentRequest.class),
                      examples =
                          @ExampleObject(
                              name = "payer-to-merchant-payment",
                              value =
                                  """
                                  {
                                    "payerWalletId": "11111111-1111-1111-1111-111111111111",
                                    "merchantWalletId": "22222222-2222-2222-2222-222222222222",
                                    "money": { "amount": "25.00", "currency": "USD" }
                                  }
                                  """)))
          @Valid
          @RequestBody
          PaymentRequest request) {
    OwnerContext ownerContext = ownerContextResolver.resolve(ownerHeader, authenticatedToken);
    PaymentExecution execution =
        paymentApplicationService.create(
            ownerContext,
            new PaymentCommand(
                request.payerWalletId(),
                request.merchantWalletId(),
                toMoney(request.money()),
                idempotencyKey));
    if (execution.risk() != null) {
      RiskResponse risk = RiskResponse.from(execution.risk());
      if (risk.outcome().equals("BLOCK")
          || risk.caseStatus().equals("DECLINED")
          || risk.caseStatus().equals("EXPIRED")
          || risk.caseStatus().equals("POLICY_BLOCKED")) {
        return ResponseEntity.unprocessableEntity().body(risk);
      }
      return ResponseEntity.accepted()
          .location(URI.create("/api/v1/payment-risk-cases/" + risk.caseId()))
          .body(risk);
    }
    PaymentResponse response =
        PaymentResponse.from(
            paymentQueryService.findForOwner(execution.payment().id(), ownerContext));
    if (execution.replayed()) {
      return ResponseEntity.ok(response);
    }
    return ResponseEntity.created(URI.create("/api/v1/payments/" + response.paymentId()))
        .body(response);
  }

  @GetMapping("/{paymentId}")
  @Operation(
      operationId = "getPayment",
      summary = "Get a payment and derived refund status",
      description =
          "Returns a payment only to its payer or merchant. Unrelated owners receive 404.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public PaymentResponse find(
      @Parameter(
              name = OWNER_HEADER,
              in = ParameterIn.HEADER,
              required = false,
              example = "11111111-1111-1111-1111-111111111111",
              description =
                  "Required only for local/test use. Production uses the Keycloak-signed ledgerx_owner_id claim.")
          @RequestHeader(value = OWNER_HEADER, required = false)
          String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID paymentId) {
    return PaymentResponse.from(
        paymentQueryService.findForOwner(
            paymentId, ownerContextResolver.resolve(ownerHeader, authenticatedToken)));
  }

  @PostMapping("/{paymentId}/refunds")
  @Operation(
      operationId = "createRefund",
      summary = "Create or replay a full or partial merchant refund",
      description =
          "Only the original merchant may refund. The refund creates a compensating immutable journal and cannot exceed the original payment after concurrent requests.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        content = @Content(schema = @Schema(implementation = RefundResponse.class))),
    @ApiResponse(
        responseCode = "200",
        content = @Content(schema = @Schema(implementation = RefundResponse.class))),
    @ApiResponse(
        responseCode = "400",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "403",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "422",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<RefundResponse> refund(
      @Parameter(
              name = OWNER_HEADER,
              in = ParameterIn.HEADER,
              required = false,
              example = "22222222-2222-2222-2222-222222222222",
              description =
                  "Required only for local/test use. Production uses the Keycloak-signed ledgerx_owner_id claim.")
          @RequestHeader(value = OWNER_HEADER, required = false)
          String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @Parameter(
              name = IDEMPOTENCY_HEADER,
              in = ParameterIn.HEADER,
              required = true,
              example = "refund-demo-0001",
              description = "Client-supplied key scoped to this merchant owner.")
          @RequestHeader(IDEMPOTENCY_HEADER)
          @NotBlank
          @Size(max = 255)
          String idempotencyKey,
      @PathVariable UUID paymentId,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(
                      schema = @Schema(implementation = RefundRequest.class),
                      examples =
                          @ExampleObject(
                              name = "partial-refund",
                              value =
                                  "{ \"money\": { \"amount\": \"10.00\", \"currency\": \"USD\" } }")))
          @Valid
          @RequestBody
          RefundRequest request) {
    RefundExecution execution =
        refundApplicationService.create(
            ownerContextResolver.resolve(ownerHeader, authenticatedToken),
            new RefundCommand(paymentId, toMoney(request.money()), idempotencyKey));
    RefundResponse response = RefundResponse.from(execution.refund());
    if (execution.replayed()) {
      return ResponseEntity.ok(response);
    }
    return ResponseEntity.created(
            URI.create("/api/v1/payments/" + paymentId + "/refunds/" + response.refundId()))
        .body(response);
  }

  @GetMapping("/{paymentId}/refunds")
  public List<RefundResponse> listRefunds(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID paymentId) {
    paymentQueryService.findForOwner(
        paymentId, ownerContextResolver.resolve(ownerHeader, authenticatedToken));
    return refundRepository.findAllByPaymentIdOrderByCompletedAtAsc(paymentId).stream()
        .map(RefundResponse::from)
        .toList();
  }

  @GetMapping("/{paymentId}/refunds/{refundId}")
  public RefundResponse findRefund(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID paymentId,
      @PathVariable UUID refundId) {
    paymentQueryService.findForOwner(
        paymentId, ownerContextResolver.resolve(ownerHeader, authenticatedToken));
    return RefundResponse.from(
        refundRepository
            .findByIdAndPaymentId(refundId, paymentId)
            .orElseThrow(() -> new PaymentNotFoundException("refund was not found")));
  }

  private Money toMoney(MoneyRequest request) {
    try {
      return new Money(new BigDecimal(request.amount()), request.currency());
    } catch (NumberFormatException exception) {
      throw new MalformedRequestException("money amount must be a decimal string", exception);
    } catch (IllegalArgumentException exception) {
      throw new PaymentValidationException(exception.getMessage());
    }
  }
}
