package com.ledgerx.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoFundingApiIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private WalletAccountService walletAccountService;
  @Autowired private LedgerBalanceQueryService balanceQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Test
  void topUpIsBalancedVisibleAndSafeToRetry() throws Exception {
    WalletRegistration wallet = walletAccountService.createWallet(OwnerType.PERSON);
    String key = UUID.randomUUID().toString();

    MvcResult first =
        fund(wallet.ownerId(), wallet.walletAccountId(), key, "25.00")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value(25.00))
            .andReturn();
    JsonNode receipt = objectMapper.readTree(first.getResponse().getContentAsString());
    UUID transactionId = UUID.fromString(receipt.get("ledgerTransactionId").asText());

    fund(wallet.ownerId(), wallet.walletAccountId(), key, "25.00")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fundingId").value(receipt.get("fundingId").asText()));
    assertThat(balanceQueryService.balanceOf(wallet.walletAccountId()).amount())
        .isEqualByComparingTo("25.00");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledgerx.ledger_entries WHERE ledger_transaction_id = ?",
                Integer.class,
                transactionId))
        .isEqualTo(2);
    mockMvc
        .perform(get("/api/v1/activity").header("X-LedgerX-Owner-Id", wallet.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kind").value("TOP_UP"))
        .andExpect(jsonPath("$[0].amount").value("25.00"));
    mockMvc
        .perform(
            get("/api/v1/ledger-transactions/{id}", transactionId)
                .header("X-LedgerX-Owner-Id", wallet.ownerId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(2));
  }

  @Test
  void rejectsKeyReuseAnotherOwnerAndInvalidAmounts() throws Exception {
    WalletRegistration wallet = walletAccountService.createWallet(OwnerType.PERSON);
    WalletRegistration stranger = walletAccountService.createWallet(OwnerType.PERSON);
    String key = UUID.randomUUID().toString();

    fund(wallet.ownerId(), wallet.walletAccountId(), key, "1.00").andExpect(status().isCreated());
    fund(wallet.ownerId(), wallet.walletAccountId(), key, "2.00")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    fund(stranger.ownerId(), wallet.walletAccountId(), UUID.randomUUID().toString(), "1.00")
        .andExpect(status().isNotFound());
    fund(wallet.ownerId(), wallet.walletAccountId(), UUID.randomUUID().toString(), "10000.01")
        .andExpect(status().isUnprocessableEntity());
    fund(wallet.ownerId(), wallet.walletAccountId(), UUID.randomUUID().toString(), "0.00")
        .andExpect(status().isUnprocessableEntity());
    assertThat(balanceQueryService.balanceOf(wallet.walletAccountId()).amount())
        .isEqualByComparingTo("1.00");
  }

  private org.springframework.test.web.servlet.ResultActions fund(
      UUID ownerId, UUID walletId, String key, String amount) throws Exception {
    return mockMvc.perform(
        post("/api/v1/demo/wallets/{walletId}/fundings", walletId)
            .header("X-LedgerX-Owner-Id", ownerId)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"money\":{\"amount\":\"" + amount + "\",\"currency\":\"USD\"}}"));
  }
}
