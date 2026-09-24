package com.ledgerx.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-secret browser configuration for the same-origin workbench. */
@RestController
public class UiConfigurationController {

  private final Environment environment;
  private final String issuerUri;
  private final String clientId;

  public UiConfigurationController(
      Environment environment,
      @Value("${LEDGERX_OIDC_ISSUER_URI:}") String issuerUri,
      @Value("${LEDGERX_WEB_CLIENT_ID:ledgerx-web}") String clientId) {
    this.environment = environment;
    this.issuerUri = issuerUri;
    this.clientId = clientId;
  }

  @GetMapping("/ui-config")
  public UiConfiguration config() {
    return new UiConfiguration(
        environment.acceptsProfiles(Profiles.of("prod")) ? "production" : "development",
        issuerUri,
        clientId);
  }

  public record UiConfiguration(String mode, String issuerUri, String clientId) {}
}
