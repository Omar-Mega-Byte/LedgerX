package com.ledgerx.webhook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reports queue state without treating a merchant receiver outage as application unavailability.
 */
@Component("webhookDelivery")
public class WebhookDeliveryHealthIndicator implements HealthIndicator {

  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  public WebhookDeliveryHealthIndicator(JdbcTemplate jdbcTemplate, Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try {
      Integer pending =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE status = 'PENDING'",
              Integer.class);
      Integer dead =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE status = 'DEAD'",
              Integer.class);
      Integer overdue =
          jdbcTemplate.queryForObject(
              """
              SELECT COUNT(*)
              FROM ledgerx.webhook_deliveries
              WHERE status = 'PENDING' AND queued_at < ?
              """,
              Integer.class,
              Timestamp.from(clock.instant().minus(Duration.ofMinutes(15))));
      return Health.up()
          .withDetail("pendingDeliveries", pending == null ? 0 : pending)
          .withDetail("deadDeliveries", dead == null ? 0 : dead)
          .withDetail("overdueDeliveries", overdue == null ? 0 : overdue)
          .build();
    } catch (RuntimeException exception) {
      return Health.unknown().withDetail("reason", "webhook delivery state unavailable").build();
    }
  }
}
