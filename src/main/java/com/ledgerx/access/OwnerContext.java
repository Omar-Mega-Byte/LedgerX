package com.ledgerx.access;

import java.util.Objects;
import java.util.UUID;

/**
 * Development-time caller ownership context. HTTP currently obtains it from a forgeable header;
 * real authentication will replace that adapter without changing financial application services.
 */
public record OwnerContext(UUID ownerId) {

  public OwnerContext {
    Objects.requireNonNull(ownerId, "owner id must not be null");
  }
}
