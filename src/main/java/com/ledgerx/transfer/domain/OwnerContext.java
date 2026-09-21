package com.ledgerx.transfer.domain;

import java.util.Objects;
import java.util.UUID;

/** Authenticated-owner seam. Phase 2's HTTP adapter obtains it from a development-only header. */
public record OwnerContext(UUID ownerId) {

  public OwnerContext {
    Objects.requireNonNull(ownerId, "owner id must not be null");
  }
}
