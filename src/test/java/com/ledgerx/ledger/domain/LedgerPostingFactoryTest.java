package com.ledgerx.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerx.money.CurrencyCode;
import com.ledgerx.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerPostingFactoryTest {

  private final LedgerPostingFactory factory =
      new LedgerPostingFactory(Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void createsAnImmutableBalancedJournalCandidate() {
    UUID debitAccount = UUID.randomUUID();
    UUID creditAccount = UUID.randomUUID();

    JournalPosting posting =
        factory.create(
            "Initial funding",
            List.of(
                line(debitAccount, EntrySide.DEBIT, "100.00"),
                line(creditAccount, EntrySide.CREDIT, "100.00")));

    assertThat(posting.transaction().currency()).isEqualTo(CurrencyCode.USD);
    assertThat(posting.transaction().postedAt()).isEqualTo(Instant.parse("2026-09-21T00:00:00Z"));
    assertThat(posting.entries()).hasSize(2);
    assertThat(posting.entries())
        .extracting(LedgerEntry::lineNumber)
        .containsExactly((short) 1, (short) 2);
    assertThat(posting.entries())
        .extracting(LedgerEntry::ledgerAccountId)
        .containsExactly(debitAccount, creditAccount);
  }

  @Test
  void rejectsUnbalancedEntries() {
    assertThatThrownBy(
            () ->
                factory.create(
                    "Invalid",
                    List.of(
                        line(UUID.randomUUID(), EntrySide.DEBIT, "100.00"),
                        line(UUID.randomUUID(), EntrySide.CREDIT, "99.99"))))
        .isInstanceOf(FinancialValidationException.class)
        .hasMessage("ledger transaction debits must equal credits");
  }

  @Test
  void rejectsDuplicateAccountsAndZeroPostingAmounts() {
    UUID accountId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                factory.create(
                    "Duplicate",
                    List.of(
                        line(accountId, EntrySide.DEBIT, "100.00"),
                        line(accountId, EntrySide.CREDIT, "100.00"))))
        .isInstanceOf(FinancialValidationException.class)
        .hasMessage("an account may appear only once in a ledger transaction");

    assertThatThrownBy(
            () ->
                factory.create(
                    "Zero",
                    List.of(
                        line(UUID.randomUUID(), EntrySide.DEBIT, "0.00"),
                        line(UUID.randomUUID(), EntrySide.CREDIT, "0.00"))))
        .isInstanceOf(FinancialValidationException.class)
        .hasMessage("ledger entry amount must be positive");
  }

  private PostingLine line(UUID accountId, EntrySide side, String amount) {
    return new PostingLine(accountId, side, new Money(new BigDecimal(amount), CurrencyCode.USD));
  }
}
