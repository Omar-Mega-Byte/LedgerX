package com.ledgerx.ledger.domain;

import com.ledgerx.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LedgerPostingFactory {

  // ledger_entries.amount is NUMERIC(19,2): seventeen integral digits and two fractional digits.
  private static final BigDecimal MAX_ENTRY_AMOUNT = new BigDecimal("99999999999999999.99");

  private final Clock clock;

  public LedgerPostingFactory(Clock clock) {
    this.clock = clock;
  }

  public JournalPosting create(String description, List<PostingLine> lines) {
    Objects.requireNonNull(lines, "posting lines must not be null");
    if (lines.size() < 2) {
      throw new FinancialValidationException("a ledger transaction requires at least two entries");
    }

    List<PostingLine> postingLines = List.copyOf(lines);
    CurrencyCode currency = postingLines.getFirst().amount().currency();
    Set<UUID> accountIds = new HashSet<>();
    BigDecimal debitTotal = BigDecimal.ZERO;
    BigDecimal creditTotal = BigDecimal.ZERO;

    for (PostingLine line : postingLines) {
      if (!line.amount().isPositive()) {
        throw new FinancialValidationException("ledger entry amount must be positive");
      }
      if (line.amount().amount().compareTo(MAX_ENTRY_AMOUNT) > 0) {
        throw new FinancialValidationException("ledger entry amount exceeds the supported maximum");
      }
      if (line.amount().currency() != currency) {
        throw new FinancialValidationException("all ledger entries must have the same currency");
      }
      if (!accountIds.add(line.ledgerAccountId())) {
        throw new FinancialValidationException(
            "an account may appear only once in a ledger transaction");
      }
      if (line.side() == EntrySide.DEBIT) {
        debitTotal = debitTotal.add(line.amount().amount());
      } else {
        creditTotal = creditTotal.add(line.amount().amount());
      }
    }

    if (debitTotal.compareTo(creditTotal) != 0) {
      throw new FinancialValidationException("ledger transaction debits must equal credits");
    }

    UUID transactionId = UUID.randomUUID();
    LedgerTransaction transaction =
        new LedgerTransaction(transactionId, currency, description, clock.instant());
    List<LedgerEntry> entries = new ArrayList<>();
    for (int index = 0; index < postingLines.size(); index++) {
      PostingLine line = postingLines.get(index);
      entries.add(
          new LedgerEntry(
              transactionId,
              (short) (index + 1),
              line.ledgerAccountId(),
              line.side(),
              line.amount()));
    }
    return new JournalPosting(transaction, entries);
  }
}
