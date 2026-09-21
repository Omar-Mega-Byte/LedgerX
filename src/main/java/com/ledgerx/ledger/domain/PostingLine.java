package com.ledgerx.ledger.domain;

import com.ledgerx.money.Money;
import java.util.Objects;
import java.util.UUID;

public record PostingLine(UUID ledgerAccountId, EntrySide side, Money amount) {

  public PostingLine {
    Objects.requireNonNull(ledgerAccountId, "ledger account id must not be null");
    Objects.requireNonNull(side, "entry side must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
  }
}
