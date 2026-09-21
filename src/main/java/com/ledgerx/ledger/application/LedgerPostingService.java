package com.ledgerx.ledger.application;

import com.ledgerx.ledger.domain.AccountUnavailableException;
import com.ledgerx.ledger.domain.InsufficientFundsException;
import com.ledgerx.ledger.domain.JournalPosting;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.domain.LedgerPostingFactory;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.ledger.domain.UnknownLedgerAccountException;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.ledger.persistence.LedgerEntryRepository;
import com.ledgerx.ledger.persistence.LedgerTransactionRepository;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerPostingService {

  private final LedgerPostingFactory ledgerPostingFactory;
  private final LedgerAccountRepository ledgerAccountRepository;
  private final WalletOwnerRepository walletOwnerRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final LedgerTransactionRepository ledgerTransactionRepository;
  private final LedgerBalanceQueryService ledgerBalanceQueryService;

  public LedgerPostingService(
      LedgerPostingFactory ledgerPostingFactory,
      LedgerAccountRepository ledgerAccountRepository,
      WalletOwnerRepository walletOwnerRepository,
      LedgerEntryRepository ledgerEntryRepository,
      LedgerTransactionRepository ledgerTransactionRepository,
      LedgerBalanceQueryService ledgerBalanceQueryService) {
    this.ledgerPostingFactory = ledgerPostingFactory;
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.walletOwnerRepository = walletOwnerRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.ledgerTransactionRepository = ledgerTransactionRepository;
    this.ledgerBalanceQueryService = ledgerBalanceQueryService;
  }

  @Transactional
  public UUID post(String description, List<PostingLine> lines) {
    JournalPosting posting = ledgerPostingFactory.create(description, lines);
    List<UUID> accountIds =
        posting.entries().stream().map(entry -> entry.ledgerAccountId()).sorted().toList();
    Map<UUID, LedgerAccount> accountsById = lockAndIndexAccounts(accountIds);

    validateAccounts(posting, accountsById);
    validateWalletOwners(accountsById.values());
    validateWalletBalances(posting, accountsById);

    ledgerTransactionRepository.saveAndFlush(posting.transaction());
    ledgerEntryRepository.saveAll(posting.entries());
    return posting.transaction().id();
  }

  private Map<UUID, LedgerAccount> lockAndIndexAccounts(List<UUID> accountIds) {
    List<LedgerAccount> accounts = ledgerAccountRepository.lockAllByIdInOrder(accountIds);
    if (accounts.size() != accountIds.size()) {
      throw new UnknownLedgerAccountException("one or more ledger accounts were not found");
    }
    return accounts.stream()
        .collect(java.util.stream.Collectors.toMap(LedgerAccount::id, Function.identity()));
  }

  private void validateAccounts(JournalPosting posting, Map<UUID, LedgerAccount> accountsById) {
    posting
        .entries()
        .forEach(
            entry -> {
              LedgerAccount account = accountsById.get(entry.ledgerAccountId());
              if (!account.isActive()) {
                throw new AccountUnavailableException(
                    "ledger account " + account.id() + " is not active");
              }
              if (account.currency() != entry.money().currency()) {
                throw new AccountUnavailableException(
                    "ledger account " + account.id() + " has a different currency");
              }
            });
  }

  private void validateWalletOwners(Collection<LedgerAccount> accounts) {
    List<UUID> ownerIds =
        accounts.stream()
            .filter(LedgerAccount::isWallet)
            .map(LedgerAccount::ownerId)
            .sorted()
            .toList();
    if (ownerIds.isEmpty()) {
      return;
    }

    Map<UUID, WalletOwner> ownersById =
        walletOwnerRepository.lockAllByIdInOrder(ownerIds).stream()
            .collect(java.util.stream.Collectors.toMap(WalletOwner::id, Function.identity()));
    if (ownersById.size() != ownerIds.size()) {
      throw new AccountUnavailableException("a wallet owner was not found");
    }
    if (ownersById.values().stream().anyMatch(owner -> !owner.isActive())) {
      throw new AccountUnavailableException("a wallet owner is not active");
    }
  }

  private void validateWalletBalances(
      JournalPosting posting, Map<UUID, LedgerAccount> accountsById) {
    posting
        .entries()
        .forEach(
            entry -> {
              LedgerAccount account = accountsById.get(entry.ledgerAccountId());
              if (!account.isWallet()) {
                return;
              }

              BigDecimal currentBalance = ledgerBalanceQueryService.balanceOf(account).amount();
              BigDecimal prospectiveBalance =
                  entry.side() == account.normalSide()
                      ? currentBalance.add(entry.money().amount())
                      : currentBalance.subtract(entry.money().amount());
              if (prospectiveBalance.signum() < 0) {
                throw new InsufficientFundsException(
                    "wallet account " + account.id() + " has insufficient funds");
              }
            });
  }
}
