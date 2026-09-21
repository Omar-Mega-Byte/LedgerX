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
                    Interactive documentation for LedgerX's USD wallet transfers and merchant payments.

                    **Development-only ownership seam:** `X-LedgerX-Owner-Id` is forgeable and is
                    not authentication. Do not expose this API publicly until a real authenticated
                    principal replaces it.

                    The examples are ready to edit and execute. Before a successful transfer or
                    payment, use the internal development setup to create active USD wallets and fund
                    the source wallet; public onboarding and deposit APIs are intentionally out of scope.
                    """))
        .addServersItem(new Server().url("/").description("Current LedgerX server"));
  }
}
