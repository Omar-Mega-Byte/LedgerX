package com.ledgerx.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.api.MalformedRequestException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class OwnerContextResolverTest {

  private final OwnerContextResolver resolver = new OwnerContextResolver();

  @Test
  void resolvesTheDevelopmentHeaderWhenNoAuthenticatedJwtExists() {
    UUID ownerId = UUID.randomUUID();

    OwnerContext result = resolver.resolve(ownerId.toString(), null);

    assertThat(result.ownerId()).isEqualTo(ownerId);
  }

  @Test
  void authenticatedJwtClaimOverridesTheForgeableDevelopmentHeader() {
    UUID jwtOwnerId = UUID.randomUUID();

    OwnerContext result =
        resolver.resolve(UUID.randomUUID().toString(), jwtWithOwnerId(jwtOwnerId));

    assertThat(result.ownerId()).isEqualTo(jwtOwnerId);
  }

  @Test
  void rejectsAuthenticatedJwtWithoutLedgerxOwnerIdClaim() {
    Jwt token =
        new Jwt(
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("sub", "keycloak-user"));

    assertThatThrownBy(() -> resolver.resolve(UUID.randomUUID().toString(), token))
        .isInstanceOf(OwnerIdentityException.class)
        .hasMessage("authenticated token does not contain a LedgerX owner identifier");
  }

  @Test
  void rejectsMissingDevelopmentHeaderWhenNoJwtExists() {
    assertThatThrownBy(() -> resolver.resolve(null, null))
        .isInstanceOf(MalformedRequestException.class)
        .hasMessage("owner header is required");
  }

  private Jwt jwtWithOwnerId(UUID ownerId) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(OwnerContextResolver.OWNER_ID_CLAIM, ownerId.toString()));
  }
}
