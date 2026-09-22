package com.ledgerx.transfer.api;

import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.api.ApiError;
import com.ledgerx.api.MalformedRequestException;
import com.ledgerx.api.MoneyRequest;
import com.ledgerx.money.Money;
import com.ledgerx.transfer.application.TransferApplicationService;
import com.ledgerx.transfer.application.TransferExecution;
import com.ledgerx.transfer.application.TransferQueryService;
import com.ledgerx.transfer.domain.TransferCommand;
import com.ledgerx.transfer.domain.TransferValidationException;
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

/** HTTP boundary for transfers, with profile-specific caller identity resolution. */
@Validated
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(
    name = "Wallet Transfers",
    description = "Synchronous, atomic, idempotent USD wallet-to-wallet transfers.")
public class TransferController {

  static final String OWNER_HEADER = "X-LedgerX-Owner-Id";
  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final TransferApplicationService transferApplicationService;
  private final TransferQueryService transferQueryService;
  private final OwnerContextResolver ownerContextResolver;

  public TransferController(
      TransferApplicationService transferApplicationService,
      TransferQueryService transferQueryService,
      OwnerContextResolver ownerContextResolver) {
    this.transferApplicationService = transferApplicationService;
    this.transferQueryService = transferQueryService;
    this.ownerContextResolver = ownerContextResolver;
  }

  @PostMapping
  @Operation(
      operationId = "createTransfer",
      summary = "Create or replay a USD wallet transfer",
      description =
          """
          Debits the source wallet and credits the destination wallet through one balanced immutable
          journal. The caller must own the source wallet; either PERSON or MERCHANT wallets may
          send or receive.

          Use a fresh `Idempotency-Key` for a new request. Repeating the same owner, key, and
          canonical request returns the original completed transfer without another financial
          effect. The example values are placeholders: replace the wallet and owner UUIDs with
          active USD data created through the internal development setup.
          """)
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description =
            "A new transfer completed atomically. The Location header points to its GET resource.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TransferResponse.class),
                examples =
                    @ExampleObject(
                        name = "completed-transfer",
                        value =
                            """
                            {
                              "transferId": "33333333-3333-3333-3333-333333333333",
                              "sourceWalletId": "11111111-1111-1111-1111-111111111111",
                              "destinationWalletId": "22222222-2222-2222-2222-222222222222",
                              "money": { "amount": "25.00", "currency": "USD" },
                              "status": "COMPLETED",
                              "ledgerTransactionId": "44444444-4444-4444-4444-444444444444",
                              "completedAt": "2030-01-02T03:04:05Z"
                            }
                            """))),
    @ApiResponse(
        responseCode = "200",
        description =
            "An identical completed request was replayed; no additional transfer was posted.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TransferResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed JSON, UUID, currency, required header, or request field.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "403",
        description = "The supplied owner does not own the source wallet.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(
                        name = "source-owner-required",
                        value =
                            """
                            {
                              "timestamp": "2030-01-02T03:04:05Z",
                              "status": 403,
                              "code": "TRANSFER_NOT_AUTHORIZED",
                              "message": "caller does not own the source wallet",
                              "path": "/api/v1/transfers",
                              "details": []
                            }
                            """))),
    @ApiResponse(
        responseCode = "404",
        description = "The source or destination wallet was not found.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description =
            "The key belongs to a different request, is still processing, or a retryable database concurrency conflict occurred.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(
                        name = "key-reused",
                        value =
                            """
                            {
                              "timestamp": "2030-01-02T03:04:05Z",
                              "status": 409,
                              "code": "IDEMPOTENCY_KEY_REUSED",
                              "message": "idempotency key was already used for a different request",
                              "path": "/api/v1/transfers",
                              "details": []
                            }
                            """))),
    @ApiResponse(
        responseCode = "422",
        description =
            "The transfer is valid JSON but cannot be processed, for example due to funds or wallet state.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class),
                examples =
                    @ExampleObject(
                        name = "insufficient-funds",
                        value =
                            """
                            {
                              "timestamp": "2030-01-02T03:04:05Z",
                              "status": 422,
                              "code": "TRANSFER_NOT_PROCESSABLE",
                              "message": "wallet account has insufficient funds",
                              "path": "/api/v1/transfers",
                              "details": []
                            }
                            """))),
    @ApiResponse(
        responseCode = "500",
        description =
            "Unexpected server error; implementation details are intentionally not exposed.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class)))
  })
  public ResponseEntity<TransferResponse> create(
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
              example = "transfer-demo-0001",
              description =
                  "Client-supplied key, scoped to the owner. Reuse it only to replay the same request.")
          @RequestHeader(IDEMPOTENCY_HEADER)
          @NotBlank
          @Size(max = 255)
          String idempotencyKey,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              description = "Replace the placeholder UUIDs with prepared wallets before executing.",
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = TransferRequest.class),
                      examples =
                          @ExampleObject(
                              name = "usd-wallet-transfer",
                              value =
                                  """
                                  {
                                    "sourceWalletId": "11111111-1111-1111-1111-111111111111",
                                    "destinationWalletId": "22222222-2222-2222-2222-222222222222",
                                    "money": { "amount": "25.00", "currency": "USD" }
                                  }
                                  """)))
          @Valid
          @RequestBody
          TransferRequest request) {
    TransferExecution execution =
        transferApplicationService.transfer(
            ownerContextResolver.resolve(ownerHeader, authenticatedToken),
            new TransferCommand(
                request.sourceWalletId(),
                request.destinationWalletId(),
                toMoney(request.money()),
                idempotencyKey));
    TransferResponse response = TransferResponse.from(execution.transfer());
    if (execution.replayed()) {
      return ResponseEntity.ok(response);
    }
    return ResponseEntity.created(URI.create("/api/v1/transfers/" + response.transferId()))
        .body(response);
  }

  @GetMapping("/{transferId}")
  @Operation(
      operationId = "getTransfer",
      summary = "Get a completed transfer",
      description =
          "Returns a completed transfer only when the supplied development owner owns either participating wallet. Unrelated owners receive 404.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "The caller owns the source or destination wallet.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TransferResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed transfer UUID or owner header.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        description = "The transfer does not exist or is unrelated to the supplied owner.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ApiError.class)))
  })
  public TransferResponse find(
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
      @Parameter(
              name = "transferId",
              in = ParameterIn.PATH,
              required = true,
              example = "33333333-3333-3333-3333-333333333333",
              description = "UUID returned by POST /api/v1/transfers.")
          @PathVariable
          UUID transferId) {
    return TransferResponse.from(
        transferQueryService.findForOwner(
            transferId, ownerContextResolver.resolve(ownerHeader, authenticatedToken)));
  }

  private Money toMoney(MoneyRequest request) {
    try {
      return new Money(new BigDecimal(request.amount()), request.currency());
    } catch (NumberFormatException exception) {
      throw new MalformedRequestException("money amount must be a decimal string", exception);
    } catch (IllegalArgumentException exception) {
      throw new TransferValidationException(exception.getMessage());
    }
  }
}
