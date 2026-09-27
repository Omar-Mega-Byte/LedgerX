package com.ledgerx.operations.api;

import com.ledgerx.webhook.WebhookEncryptionRotationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only maintenance route; production security guards all operations routes. */
@Validated
@RestController
@RequestMapping("/api/v1/operations/webhook-keys")
public class WebhookKeyRotationController {

  private final WebhookEncryptionRotationService service;

  public WebhookKeyRotationController(WebhookEncryptionRotationService service) {
    this.service = service;
  }

  @PostMapping("/reencrypt")
  public RotationResult reencrypt(
      @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
      @AuthenticationPrincipal Jwt token) {
    return new RotationResult(
        service.reencryptBatch(limit, token == null ? "local-operator" : token.getSubject()));
  }

  public record RotationResult(int reencrypted) {}
}
