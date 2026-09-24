package com.ledgerx.ledger.api;

import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.LedgerEntry;
import com.ledgerx.ledger.domain.LedgerTransaction;
import com.ledgerx.ledger.domain.LedgerTransactionNotFoundException;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.ledger.persistence.LedgerEntryRepository;
import com.ledgerx.ledger.persistence.LedgerTransactionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only journal evidence visible only to a participating wallet owner. */
@RestController
@RequestMapping("/api/v1/ledger-transactions")
public class LedgerJournalController {

  private final OwnerContextResolver ownerContextResolver;
  private final LedgerTransactionRepository transactionRepository;
  private final LedgerEntryRepository entryRepository;
  private final LedgerAccountRepository accountRepository;

  public LedgerJournalController(
      OwnerContextResolver ownerContextResolver,
      LedgerTransactionRepository transactionRepository,
      LedgerEntryRepository entryRepository,
      LedgerAccountRepository accountRepository) {
    this.ownerContextResolver = ownerContextResolver;
    this.transactionRepository = transactionRepository;
    this.entryRepository = entryRepository;
    this.accountRepository = accountRepository;
  }

  @GetMapping("/{transactionId}")
  @Transactional(readOnly = true)
  public JournalResponse find(
      @RequestHeader(value = "X-LedgerX-Owner-Id", required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID transactionId) {
    UUID ownerId = ownerContextResolver.resolve(ownerHeader, authenticatedToken).ownerId();
    LedgerTransaction transaction =
        transactionRepository
            .findById(transactionId)
            .orElseThrow(LedgerTransactionNotFoundException::new);
    List<LedgerEntry> entries =
        entryRepository.findAllByLedgerTransactionIdOrderByLineNumberAsc(transactionId);
    boolean participant =
        entries.stream()
            .anyMatch(
                entry ->
                    accountRepository
                        .findById(entry.ledgerAccountId())
                        .map(account -> ownerId.equals(account.ownerId()))
                        .orElse(false));
    if (!participant) {
      throw new LedgerTransactionNotFoundException();
    }
    return response(transaction, entries);
  }

  public JournalResponse findForOperator(UUID transactionId) {
    LedgerTransaction transaction =
        transactionRepository
            .findById(transactionId)
            .orElseThrow(LedgerTransactionNotFoundException::new);
    return response(
        transaction,
        entryRepository.findAllByLedgerTransactionIdOrderByLineNumberAsc(transactionId));
  }

  private JournalResponse response(LedgerTransaction transaction, List<LedgerEntry> entries) {
    return new JournalResponse(
        transaction.id(),
        transaction.description(),
        transaction.currency().name(),
        transaction.postedAt(),
        entries.stream()
            .map(
                entry ->
                    new JournalEntryResponse(
                        entry.ledgerAccountId(),
                        entry.side(),
                        entry.money().amount().toPlainString(),
                        entry.lineNumber()))
            .toList());
  }

  public record JournalResponse(
      UUID transactionId,
      String description,
      String currency,
      Instant postedAt,
      List<JournalEntryResponse> entries) {}

  public record JournalEntryResponse(
      UUID walletOrAccountId, EntrySide side, String amount, short lineNumber) {}
}
