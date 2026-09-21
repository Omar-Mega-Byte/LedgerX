package com.ledgerx.ledger.domain;

import java.util.List;
import java.util.Objects;

public record JournalPosting(LedgerTransaction transaction, List<LedgerEntry> entries) {

  public JournalPosting {
    Objects.requireNonNull(transaction, "transaction must not be null");
    entries = List.copyOf(entries);
  }
}
