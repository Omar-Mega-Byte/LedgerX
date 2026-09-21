package com.ledgerx.ledger.domain;

import com.ledgerx.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private CurrencyCode currency;

  @Column(nullable = false, updatable = false)
  private String description;

  @Column(name = "posted_at", nullable = false, updatable = false)
  private Instant postedAt;

  protected LedgerTransaction() {}

  LedgerTransaction(UUID id, CurrencyCode currency, String description, Instant postedAt) {
    this.id = Objects.requireNonNull(id, "transaction id must not be null");
    this.currency = Objects.requireNonNull(currency, "currency must not be null");
    if (description == null || description.isBlank() || description.length() > 250) {
      throw new FinancialValidationException(
          "description must contain at most 250 non-blank characters");
    }
    this.description = description;
    this.postedAt = Objects.requireNonNull(postedAt, "posted at must not be null");
  }

  public UUID id() {
    return id;
  }

  public CurrencyCode currency() {
    return currency;
  }

  public String description() {
    return description;
  }

  public Instant postedAt() {
    return postedAt;
  }
}
