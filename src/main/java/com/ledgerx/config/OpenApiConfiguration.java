package com.ledgerx.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

  @Bean
  OpenAPI ledgerXOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("LedgerX API")
                .version("v1")
                .description(
                    """
                    Interactive reference for LedgerX USD wallets, transfers, merchant payments,
                    refunds, webhooks, and operator workflows.

                    **Authentication:** Production requires a Keycloak-signed JWT with the configured
                    issuer and `ledgerx-api` audience. The signed `ledgerx_owner_id` claim scopes
                    owner requests; the `ledgerx-operator` role gates `/api/v1/operations/**`.
                    `X-LedgerX-Owner-Id` works only in local/test profiles and is a forgeable
                    development seam, not authentication.

                    Provision owners and wallets through the operator workflow before using the
                    financial examples. Local demo top-up supplies test funds; public onboarding
                    and real deposit APIs are outside this project's scope.
                    """))
        .addServersItem(new Server().url("/").description("Current LedgerX server"));
  }
}
