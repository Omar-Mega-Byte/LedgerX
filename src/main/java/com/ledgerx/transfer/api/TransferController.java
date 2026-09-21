package com.ledgerx.transfer.api;

import com.ledgerx.money.Money;
import com.ledgerx.transfer.application.TransferApplicationService;
import com.ledgerx.transfer.application.TransferExecution;
import com.ledgerx.transfer.application.TransferQueryService;
import com.ledgerx.transfer.domain.OwnerContext;
import com.ledgerx.transfer.domain.TransferCommand;
import com.ledgerx.transfer.domain.TransferValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only HTTP boundary. X-LedgerX-Owner-Id is forgeable and must be replaced by an
 * authenticated principal before public deployment.
 */
@Validated
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

  static final String OWNER_HEADER = "X-LedgerX-Owner-Id";
  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final TransferApplicationService transferApplicationService;
  private final TransferQueryService transferQueryService;

  public TransferController(
      TransferApplicationService transferApplicationService,
      TransferQueryService transferQueryService) {
    this.transferApplicationService = transferApplicationService;
    this.transferQueryService = transferQueryService;
  }

  @PostMapping
  public ResponseEntity<TransferResponse> create(
      @RequestHeader(OWNER_HEADER) String ownerHeader,
      @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody TransferRequest request) {
    TransferExecution execution =
        transferApplicationService.transfer(
            ownerContext(ownerHeader),
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
  public TransferResponse find(
      @RequestHeader(OWNER_HEADER) String ownerHeader, @PathVariable UUID transferId) {
    return TransferResponse.from(
        transferQueryService.findForOwner(transferId, ownerContext(ownerHeader)));
  }

  private OwnerContext ownerContext(String ownerHeader) {
    try {
      return new OwnerContext(UUID.fromString(ownerHeader));
    } catch (IllegalArgumentException exception) {
      throw new MalformedTransferRequestException("owner header must be a UUID", exception);
    }
  }

  private Money toMoney(MoneyRequest request) {
    try {
      return new Money(new BigDecimal(request.amount()), request.currency());
    } catch (NumberFormatException exception) {
      throw new MalformedTransferRequestException(
          "money amount must be a decimal string", exception);
    } catch (IllegalArgumentException exception) {
      throw new TransferValidationException(exception.getMessage());
    }
  }
}
