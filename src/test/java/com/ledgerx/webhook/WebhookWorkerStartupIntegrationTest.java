package com.ledgerx.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {"ledgerx.webhooks.dispatcher-enabled=true", "ledgerx.webhooks.poll-delay=PT1H"})
@ActiveProfiles("test")
@Testcontainers
class WebhookWorkerStartupIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "ledgerx.webhooks.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
  }

  @Test
  void verifiesEncryptionBeforeScheduledDispatcherStarts() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledgerx.webhook_endpoints", Integer.class))
        .isZero();
  }
}
