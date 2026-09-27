package com.ledgerx.wallet.api;

import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.api.MalformedRequestException;
import com.ledgerx.api.MoneyRequest;
import com.ledgerx.ledger.domain.FinancialValidationException;
import com.ledgerx.money.Money;
import com.ledgerx.wallet.application.DemoFundingService;
import com.ledgerx.wallet.application.DemoFundingService.FundingExecution;
import com.ledgerx.wallet.application.DemoFundingService.FundingReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-money endpoint that is never registered with the production profile. */
@Validated
@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/demo/wallets")
public class DemoFundingController {

  private final OwnerContextResolver ownerContextResolver;
  private final DemoFundingService fundingService;

  public DemoFundingController(
      OwnerContextResolver ownerContextResolver, DemoFundingService fundingService) {
    this.ownerContextResolver = ownerContextResolver;
    this.fundingService = fundingService;
  }

  @PostMapping("/{walletId}/fundings")
  public ResponseEntity<FundingReceipt> add(
      @PathVariable UUID walletId,
      @RequestHeader(value = "X-LedgerX-Owner-Id", required = false) String ownerHeader,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @Valid @RequestBody FundingRequest request) {
    MoneyRequest input = request.money();
    Money money;
    try {
      money = new Money(new BigDecimal(input.amount()), input.currency());
    } catch (NumberFormatException exception) {
      throw new MalformedRequestException("money amount must be a decimal string", exception);
    } catch (IllegalArgumentException exception) {
      throw new FinancialValidationException(exception.getMessage());
    }
    FundingExecution execution =
        fundingService.add(
            ownerContextResolver.resolve(ownerHeader, authenticatedToken).ownerId(),
            walletId,
            money,
            idempotencyKey);
    return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(execution.receipt());
  }

  public record FundingRequest(@NotNull @Valid MoneyRequest money) {}
}
