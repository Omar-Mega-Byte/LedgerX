package com.ledgerx.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Bounded operational state from durable tables; individual event and merchant IDs are never tags.
 */
@Component
public class OperationalMetrics {

  private final MeterRegistry registry;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final KafkaAdmin kafkaAdmin;
  private final boolean kafkaRequired;
  private final boolean reconciliationEnabled;

  public OperationalMetrics(
      MeterRegistry registry,
      JdbcTemplate jdbc,
      Clock clock,
      KafkaAdmin kafkaAdmin,
      @Value("${ledgerx.outbox.publisher-enabled:false}") boolean publisherEnabled,
      @Value("${ledgerx.kafka.consumer-enabled:false}") boolean consumerEnabled,
      @Value("${ledgerx.webhooks.consumer-enabled:false}") boolean webhookConsumerEnabled,
      @Value("${ledgerx.reconciliation.enabled:false}") boolean reconciliationEnabled) {
    this.registry = registry;
    this.jdbc = jdbc;
    this.clock = clock;
    this.kafkaAdmin = kafkaAdmin;
    this.kafkaRequired = publisherEnabled || consumerEnabled || webhookConsumerEnabled;
    this.reconciliationEnabled = reconciliationEnabled;
    Gauge.builder("ledgerx.database.available", this, metrics -> metrics.databaseAvailable())
        .register(registry);
    Gauge.builder("ledgerx.kafka.broker.available", this, metrics -> metrics.kafkaAvailable())
        .register(registry);
    Gauge.builder("ledgerx.outbox.pending", this, metrics -> metrics.outboxPending())
        .register(registry);
    Gauge.builder("ledgerx.outbox.stalled", this, metrics -> metrics.outboxStalled())
        .register(registry);
    Gauge.builder("ledgerx.outbox.oldest_pending_age_seconds", this, metrics -> metrics.outboxAge())
        .register(registry);
    Gauge.builder("ledgerx.webhook.pending", this, metrics -> metrics.webhookPending())
        .register(registry);
    Gauge.builder("ledgerx.webhook.dead", this, metrics -> metrics.webhookDead())
        .register(registry);
    Gauge.builder("ledgerx.webhook.stalled", this, metrics -> metrics.webhookStalled())
        .register(registry);
    Gauge.builder(
            "ledgerx.reconciliation.enabled",
            this,
            metrics -> metrics.reconciliationEnabled ? 1 : 0)
        .register(registry);
    Gauge.builder(
            "ledgerx.reconciliation.last_run_age_seconds",
            this,
            metrics -> metrics.reconciliationAge())
        .register(registry);
    Gauge.builder(
            "ledgerx.reconciliation.last_run_failed", this, metrics -> metrics.lastRunFailed())
        .register(registry);
    Gauge.builder(
            "ledgerx.reconciliation.last_run_critical_findings",
            this,
            metrics -> metrics.lastRunCriticalFindings())
        .register(registry);
    registry.counter("ledgerx.outbox.publish", "result", "success");
    registry.counter("ledgerx.outbox.publish", "result", "retry");
    registry.counter("ledgerx.kafka.dead_letter.publish");
  }

  public void outboxPublished() {
    registry.counter("ledgerx.outbox.publish", "result", "success").increment();
  }

  public void outboxRetried() {
    registry.counter("ledgerx.outbox.publish", "result", "retry").increment();
  }

  public void deadLetterPublished() {
    registry.counter("ledgerx.kafka.dead_letter.publish").increment();
  }

  private double databaseAvailable() {
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      return 1;
    } catch (RuntimeException exception) {
      return 0;
    }
  }

  private double kafkaAvailable() {
    if (!kafkaRequired) {
      return -1;
    }
    try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
      return admin.describeCluster().nodes().get(2, TimeUnit.SECONDS).isEmpty() ? 0 : 1;
    } catch (Exception exception) {
      return 0;
    }
  }

  private double outboxPending() {
    return count("SELECT COUNT(*) FROM ledgerx.outbox_events WHERE status = 'PENDING'");
  }

  private double outboxStalled() {
    return count(
        """
        SELECT COUNT(*) FROM ledgerx.outbox_events
        WHERE (status = 'PENDING' AND next_attempt_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
           OR (status = 'IN_FLIGHT' AND lease_until < CURRENT_TIMESTAMP)
        """);
  }

  private double outboxAge() {
    try {
      Timestamp oldest =
          jdbc.queryForObject(
              "SELECT MIN(occurred_at) FROM ledgerx.outbox_events WHERE status <> 'PUBLISHED'",
              Timestamp.class);
      return oldest == null
          ? 0
          : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toSeconds());
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private double webhookPending() {
    return count("SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE status = 'PENDING'");
  }

  private double webhookDead() {
    return count("SELECT COUNT(*) FROM ledgerx.webhook_deliveries WHERE status = 'DEAD'");
  }

  private double webhookStalled() {
    return count(
        """
        SELECT COUNT(*) FROM ledgerx.webhook_deliveries d
        JOIN ledgerx.webhook_endpoints e ON e.id = d.webhook_endpoint_id
        WHERE (d.status = 'PENDING' AND e.status = 'ACTIVE'
               AND d.next_attempt_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
           OR (d.status = 'IN_FLIGHT' AND d.lease_until < CURRENT_TIMESTAMP)
        """);
  }

  private double reconciliationAge() {
    if (!reconciliationEnabled) {
      return 0;
    }
    try {
      Timestamp started =
          jdbc.queryForObject(
              "SELECT MAX(started_at) FROM ledgerx.reconciliation_runs", Timestamp.class);
      return started == null
          ? -1
          : Math.max(0, Duration.between(started.toInstant(), clock.instant()).toSeconds());
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private double lastRunFailed() {
    if (!reconciliationEnabled) {
      return 0;
    }
    return count(
        """
        SELECT COUNT(*) FROM (
          SELECT status FROM ledgerx.reconciliation_runs ORDER BY started_at DESC LIMIT 1
        ) latest WHERE status = 'FAILED'
        """);
  }

  private double lastRunCriticalFindings() {
    if (!reconciliationEnabled) {
      return 0;
    }
    return count(
        """
        SELECT COUNT(*) FROM ledgerx.reconciliation_findings
        WHERE severity = 'CRITICAL' AND reconciliation_run_id = (
          SELECT id FROM ledgerx.reconciliation_runs ORDER BY started_at DESC LIMIT 1
        )
        """);
  }

  private double count(String sql) {
    try {
      Long value = jdbc.queryForObject(sql, Long.class);
      return value == null ? Double.NaN : value.doubleValue();
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }
}
