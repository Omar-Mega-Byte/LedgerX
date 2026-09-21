package com.ledgerx.ledger.domain;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

  @Id private UUID id;

  @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
  private UUID ledgerTransactionId;

  @Column(name = "line_number", nullable = false, updatable = false)
  private short lineNumber;

  @Column(name = "ledger_account_id", nullable = false, updatable = false)
  private UUID ledgerAccountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private EntrySide side;

  @Column(nullable = false, updatable = false, precision = 19, scale = Money.SCALE)
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private CurrencyCode currency;

  protected LedgerEntry() {}

  LedgerEntry(
      UUID ledgerTransactionId,
      short lineNumber,
      UUID ledgerAccountId,
      EntrySide side,
      Money money) {
    this.id = UUID.randomUUID();
    this.ledgerTransactionId =
        Objects.requireNonNull(ledgerTransactionId, "ledger transaction id must not be null");
    if (lineNumber < 1) {
      throw new FinancialValidationException("line number must be positive");
    }
    this.lineNumber = lineNumber;
    this.ledgerAccountId =
        Objects.requireNonNull(ledgerAccountId, "ledger account id must not be null");
    this.side = Objects.requireNonNull(side, "entry side must not be null");
    Objects.requireNonNull(money, "money must not be null");
    if (!money.isPositive()) {
      throw new FinancialValidationException("ledger entry amount must be positive");
    }
    this.amount = money.amount();
    this.currency = money.currency();
  }

  public UUID id() {
    return id;
  }

  public UUID ledgerTransactionId() {
    return ledgerTransactionId;
  }

  public short lineNumber() {
    return lineNumber;
  }

  public UUID ledgerAccountId() {
    return ledgerAccountId;
  }

  public EntrySide side() {
    return side;
  }

  public Money money() {
    return new Money(amount, currency);
  }
}
