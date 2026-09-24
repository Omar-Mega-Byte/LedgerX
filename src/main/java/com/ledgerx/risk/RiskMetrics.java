package com.ledgerx.risk;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Low-cardinality decision counters and queue gauges. */
@Component
public class RiskMetrics {

  private final MeterRegistry registry;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public RiskMetrics(MeterRegistry registry, JdbcTemplate jdbc, Clock clock) {
    this.registry = registry;
    this.jdbc = jdbc;
    this.clock = clock;
    for (String outcome : new String[] {"ALLOW", "REVIEW", "BLOCK"}) {
      registry.counter("ledgerx.risk.decisions", "outcome", outcome);
    }
    Gauge.builder("ledgerx.risk.open_cases", this, RiskMetrics::openCases).register(registry);
    Gauge.builder("ledgerx.risk.aged_cases", this, RiskMetrics::agedCases).register(registry);
  }

  public void decided(String outcome) {
    registry.counter("ledgerx.risk.decisions", "outcome", outcome).increment();
  }

  private double openCases() {
    try {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM ledgerx.risk_review_cases WHERE status = 'OPEN'", Double.class);
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private double agedCases() {
    try {
      return jdbc.queryForObject(
          """
          SELECT COUNT(*) FROM ledgerx.risk_review_cases
          WHERE status = 'OPEN' AND created_at < ?
          """,
          Double.class,
          Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }
}
