package com.ledgerx.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import com.ledgerx.wallet.application.WalletAccountService;
import com.ledgerx.wallet.application.WalletRegistration;
import com.ledgerx.wallet.domain.OwnerType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "ledgerx.kafka.consumer-enabled=false",
      "ledgerx.outbox.publisher-enabled=false",
      "ledgerx.webhooks.consumer-enabled=false",
      "ledgerx.webhooks.dispatcher-enabled=false",
      "ledgerx.reconciliation.enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers
class ProductionSecurityIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine3.24"))
          .withDatabaseName("ledgerx")
          .withUsername("ledgerx")
          .withPassword("ledgerx_test_password");

  @Autowired private MockMvc mockMvc;
  @Autowired private WalletAccountService walletAccountService;
  @Autowired private LedgerPostingService ledgerPostingService;
  @Autowired private LedgerBalanceQueryService balanceQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private JwtDecoder jwtDecoder;

  private WalletRegistration source;
  private WalletRegistration destination;

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "https://issuer.example.com/realms/ledgerx");
  }

  @BeforeEach
  void prepareWalletsAndTokens() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE ledgerx.reconciliation_findings, ledgerx.reconciliation_runs, "
            + "ledgerx.webhook_delivery_attempts, ledgerx.webhook_deliveries, "
            + "ledgerx.webhook_endpoint_idempotency, ledgerx.webhook_endpoints, "
            + "ledgerx.processed_events, ledgerx.outbox_events, ledgerx.refund_idempotency, "
            + "ledgerx.payment_idempotency, ledgerx.refunds, ledgerx.payments, "
            + "ledgerx.transfer_idempotency, ledgerx.transfers, ledgerx.ledger_entries, "
            + "ledgerx.ledger_transactions, ledgerx.ledger_accounts, ledgerx.wallet_owners");
    source = walletAccountService.createWallet(OwnerType.PERSON);
    destination = walletAccountService.createWallet(OwnerType.MERCHANT);
    UUID clearing =
        walletAccountService.createSystemAccount(AccountType.ASSET, "SECURITY-CLEARING");
    ledgerPostingService.post(
        "Security test funding",
        List.of(
            line(clearing, EntrySide.DEBIT, "25.00"),
            line(source.walletAccountId(), EntrySide.CREDIT, "25.00")));
    when(jwtDecoder.decode("source-token")).thenReturn(jwt("source-token", source.ownerId()));
    when(jwtDecoder.decode("other-token")).thenReturn(jwt("other-token", destination.ownerId()));
    when(jwtDecoder.decode("unknown-token")).thenReturn(jwt("unknown-token", UUID.randomUUID()));
    when(jwtDecoder.decode("missing-owner-token"))
        .thenReturn(
            Jwt.withTokenValue("missing-owner-token")
                .header("alg", "RS256")
                .subject("test-subject")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid token"));
    when(jwtDecoder.decode("expired-token")).thenThrow(new BadJwtException("expired token"));
  }

  @Test
  void productionRejectsMissingInvalidAndExpiredBearerTokens() throws Exception {
    mockMvc.perform(get("/api/v1/webhook-endpoints")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/webhook-endpoints").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/webhook-endpoints").header("Authorization", "Bearer expired-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void productionUsesSignedOwnerClaimAndIgnoresSpoofedDevelopmentHeader() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/transfers")
                .header("Authorization", "Bearer other-token")
                .header("X-LedgerX-Owner-Id", source.ownerId().toString())
                .header("Idempotency-Key", "spoofed-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TRANSFER_NOT_AUTHORIZED"));
    assertThat(count("ledgerx.transfers")).isZero();
    assertThat(balanceQueryService.balanceOf(source.walletAccountId()).amount())
        .isEqualByComparingTo("25.00");

    mockMvc
        .perform(
            post("/api/v1/transfers")
                .header("Authorization", "Bearer source-token")
                .header("X-LedgerX-Owner-Id", destination.ownerId().toString())
                .header("Idempotency-Key", "correct-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferRequest()))
        .andExpect(status().isCreated());
    assertThat(count("ledgerx.transfers")).isEqualTo(1);
    assertThat(balanceQueryService.balanceOf(source.walletAccountId()).amount())
        .isEqualByComparingTo("15.00");
  }

  @Test
  void unknownOwnerClaimCannotManageMerchantResources() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/webhook-endpoints")
                .header("Authorization", "Bearer unknown-token")
                .header("X-LedgerX-Owner-Id", destination.ownerId().toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("WEBHOOK_NOT_AUTHORIZED"));
    mockMvc
        .perform(
            get("/api/v1/webhook-endpoints")
                .header("Authorization", "Bearer missing-owner-token")
                .header("X-LedgerX-Owner-Id", destination.ownerId().toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OWNER_IDENTITY_INVALID"));
  }

  private Jwt jwt(String tokenValue, UUID ownerId) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "RS256")
        .subject("test-subject")
        .issuedAt(Instant.now().minusSeconds(60))
        .expiresAt(Instant.now().plusSeconds(3600))
        .claim(OwnerContextResolver.OWNER_ID_CLAIM, ownerId.toString())
        .build();
  }

  private PostingLine line(UUID accountId, EntrySide side, String amount) {
    return new PostingLine(accountId, side, new Money(new BigDecimal(amount), CurrencyCode.USD));
  }

  private String transferRequest() {
    return """
        {"sourceWalletId":"%s","destinationWalletId":"%s","money":{"amount":"10.00","currency":"USD"}}
        """
        .formatted(source.walletAccountId(), destination.walletAccountId());
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
}
