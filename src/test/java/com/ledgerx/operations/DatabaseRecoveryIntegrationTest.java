package com.ledgerx.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.payment.application.PaymentApplicationService;
import com.ledgerx.payment.domain.PaymentCommand;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@Testcontainers
class DatabaseRecoveryIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> SOURCE =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private WalletAccountService wallets;
  @Autowired private LedgerPostingService ledger;
  @Autowired private PaymentApplicationService payments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void dataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SOURCE::getJdbcUrl);
    registry.add("spring.datasource.username", SOURCE::getUsername);
    registry.add("spring.datasource.password", SOURCE::getPassword);
  }

  @Test
  void customArchiveRestoresFinancialFactsOutboxAndImmutableGuards() throws Exception {
    WalletRegistration payer = wallets.createWallet(OwnerType.PERSON);
    WalletRegistration merchant = wallets.createWallet(OwnerType.MERCHANT);
    UUID clearing = wallets.createSystemAccount(AccountType.ASSET, "RECOVERY-" + UUID.randomUUID());
    ledger.post(
        "Recovery drill funding",
        List.of(
            new PostingLine(clearing, EntrySide.DEBIT, money("50.00")),
            new PostingLine(payer.walletAccountId(), EntrySide.CREDIT, money("50.00"))));
    UUID paymentId =
        payments
            .create(
                new OwnerContext(payer.ownerId()),
                new PaymentCommand(
                    payer.walletAccountId(),
                    merchant.walletAccountId(),
                    money("12.00"),
                    "recovery-" + UUID.randomUUID()))
            .payment()
            .id();
    UUID eventId =
        jdbc.queryForObject(
            "SELECT id FROM ledgerx.outbox_events WHERE aggregate_id = ?", UUID.class, paymentId);
    String eventPayload =
        jdbc.queryForObject(
            "SELECT payload::text FROM ledgerx.outbox_events WHERE id = ?", String.class, eventId);
    assertThat(meters.get("ledgerx.database.available").gauge().value()).isEqualTo(1);
    assertThat(meters.get("ledgerx.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ledgerx_outbox_pending")))
        .andExpect(content().string(containsString("ledgerx_database_available")))
        .andExpect(content().string(containsString("ledgerx_kafka_broker_available")))
        .andExpect(content().string(containsString("ledgerx_webhook_dead")))
        .andExpect(content().string(containsString("ledgerx_reconciliation_enabled")))
        .andExpect(content().string(containsString("ledgerx_kafka_dead_letter_publish_total")));

    Path archive = Files.createTempFile("ledgerx-recovery-", ".dump");
    try {
      org.testcontainers.containers.Container.ExecResult dump =
          SOURCE.execInContainer(
              "pg_dump",
              "-Fc",
              "--no-owner",
              "--no-acl",
              "-U",
              "ledgerx",
              "-d",
              "ledgerx",
              "-f",
              "/tmp/ledgerx.dump");
      assertThat(dump.getExitCode()).describedAs(dump.getStderr()).isZero();
      SOURCE.copyFileFromContainer("/tmp/ledgerx.dump", archive.toString());
      assertThat(Files.size(archive)).isGreaterThan(0);

      try (PostgreSQLContainer<?> restored =
          new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
              .withDatabaseName("ledgerx")
              .withUsername("ledgerx")
              .withPassword("ledgerx_test_password")) {
        restored.start();
        restored.copyFileToContainer(MountableFile.forHostPath(archive), "/tmp/ledgerx.dump");
        org.testcontainers.containers.Container.ExecResult restore =
            restored.execInContainer(
                "pg_restore",
                "--exit-on-error",
                "--no-owner",
                "--no-acl",
                "-U",
                "ledgerx",
                "-d",
                "ledgerx",
                "/tmp/ledgerx.dump");
        assertThat(restore.getExitCode()).describedAs(restore.getStderr()).isZero();
        try (var connection =
                DriverManager.getConnection(
                    restored.getJdbcUrl(), restored.getUsername(), restored.getPassword());
            var statement = connection.createStatement()) {
          try (var rows =
              statement.executeQuery(
                  "SELECT p.amount, e.id, e.payload::text FROM ledgerx.payments p "
                      + "JOIN ledgerx.outbox_events e ON e.aggregate_id = p.id "
                      + "WHERE p.id = '"
                      + paymentId
                      + "'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("12.00");
            assertThat(rows.getObject(2, UUID.class)).isEqualTo(eventId);
            assertThat(rows.getString(3)).isEqualTo(eventPayload);
          }
          try (var rows =
              statement.executeQuery(
                  "SELECT COUNT(*) FROM ledgerx.flyway_schema_history WHERE success")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(14);
          }
          assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ledgerx.ledger_entries"))
              .isInstanceOf(SQLException.class);
        }
      }
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  private Money money(String amount) {
    return new Money(new BigDecimal(amount), CurrencyCode.USD);
  }
}
