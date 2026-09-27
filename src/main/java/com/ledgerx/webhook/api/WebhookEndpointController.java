package com.ledgerx.webhook.api;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.webhook.WebhookEndpointApplicationService;
import com.ledgerx.webhook.WebhookEndpointExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/webhook-endpoints")
public class WebhookEndpointController {

  private static final String OWNER_HEADER = "X-LedgerX-Owner-Id";
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final WebhookEndpointApplicationService endpointService;
  private final OwnerContextResolver ownerContextResolver;

  public WebhookEndpointController(
      WebhookEndpointApplicationService endpointService,
      OwnerContextResolver ownerContextResolver) {
    this.endpointService = endpointService;
    this.ownerContextResolver = ownerContextResolver;
  }

  @PostMapping
  public ResponseEntity<WebhookEndpointResponse> create(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @RequestHeader(IDEMPOTENCY_HEADER) @NotBlank @Size(max = 255) String idempotencyKey,
      @Valid @RequestBody WebhookEndpointRequest request) {
    WebhookEndpointExecution execution =
        endpointService.create(
            owner(ownerHeader, authenticatedToken),
            request.url(),
            request.eventTypes(),
            request.signingSecret(),
            idempotencyKey);
    WebhookEndpointResponse response = WebhookEndpointResponse.from(execution.endpoint());
    if (execution.replayed()) {
      return ResponseEntity.ok(response);
    }
    return ResponseEntity.created(URI.create("/api/v1/webhook-endpoints/" + response.endpointId()))
        .body(response);
  }

  @GetMapping
  public List<WebhookEndpointResponse> list(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken) {
    return endpointService.list(owner(ownerHeader, authenticatedToken)).stream()
        .map(WebhookEndpointResponse::from)
        .toList();
  }

  @GetMapping("/{endpointId}")
  public WebhookEndpointResponse find(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId) {
    return WebhookEndpointResponse.from(
        endpointService.find(owner(ownerHeader, authenticatedToken), endpointId));
  }

  @PostMapping("/{endpointId}/disable")
  public WebhookEndpointResponse disable(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId) {
    return WebhookEndpointResponse.from(
        endpointService.disable(owner(ownerHeader, authenticatedToken), endpointId));
  }

  @PostMapping("/{endpointId}/rotate-secret")
  public WebhookEndpointResponse rotateSecret(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId,
      @Valid @RequestBody WebhookSecretRotationRequest request) {
    return WebhookEndpointResponse.from(
        endpointService.rotateSecret(
            owner(ownerHeader, authenticatedToken), endpointId, request.signingSecret()));
  }

  @GetMapping("/{endpointId}/deliveries")
  public List<WebhookDeliveryResponse> listDeliveries(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return endpointService
        .listDeliveries(owner(ownerHeader, authenticatedToken), endpointId, limit)
        .stream()
        .map(WebhookDeliveryResponse::from)
        .toList();
  }

  @GetMapping("/{endpointId}/deliveries/{deliveryId}")
  public WebhookDeliveryResponse findDelivery(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId,
      @PathVariable UUID deliveryId) {
    return WebhookDeliveryResponse.from(
        endpointService.findDelivery(
            owner(ownerHeader, authenticatedToken), endpointId, deliveryId));
  }

  @PostMapping("/{endpointId}/deliveries/{deliveryId}/replay")
  public ResponseEntity<Void> replay(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId,
      @PathVariable UUID deliveryId) {
    endpointService.replay(owner(ownerHeader, authenticatedToken), endpointId, deliveryId);
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/{endpointId}/deliveries/{deliveryId}/attempts")
  public List<WebhookDeliveryAttemptResponse> listAttempts(
      @RequestHeader(value = OWNER_HEADER, required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID endpointId,
      @PathVariable UUID deliveryId,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page) {
    return endpointService
        .listAttempts(owner(ownerHeader, authenticatedToken), endpointId, deliveryId, limit, page)
        .stream()
        .map(WebhookDeliveryAttemptResponse::from)
        .toList();
  }

  private OwnerContext owner(String ownerHeader, Jwt authenticatedToken) {
    return ownerContextResolver.resolve(ownerHeader, authenticatedToken);
  }
}
