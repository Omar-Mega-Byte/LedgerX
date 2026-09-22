package com.ledgerx.access;

import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated caller ownership context passed to financial application services. HTTP resolves it
 * from a Keycloak-signed JWT in production and from the development header in local/test.
 */
public record OwnerContext(UUID ownerId) {

  public OwnerContext {
    Objects.requireNonNull(ownerId, "owner id must not be null");
  }
}
