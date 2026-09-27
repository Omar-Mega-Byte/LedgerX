package com.ledgerx.risk;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** A missing active policy is a payment configuration fault. */
@Component("riskPolicy")
public class RiskPolicyHealthIndicator implements HealthIndicator {

  private final PaymentRiskService riskService;

  public RiskPolicyHealthIndicator(PaymentRiskService riskService) {
    this.riskService = riskService;
  }

  @Override
  public Health health() {
    try {
      PaymentRiskService.Policy policy = riskService.activePolicy();
      return Health.up()
          .withDetail("activeVersion", policy.versionNumber())
          .withDetail("enabled", policy.enabled())
          .build();
    } catch (RuntimeException exception) {
      return Health.down().withDetail("reason", "active risk policy unavailable").build();
    }
  }
}
