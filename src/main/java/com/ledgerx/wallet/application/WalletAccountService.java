package com.ledgerx.wallet.application;

import com.ledgerx.ledger.application.LedgerBalanceQueryService;
import com.ledgerx.ledger.domain.AccountType;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.money.CurrencyCode;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletAccountService {

  private final WalletOwnerRepository walletOwnerRepository;
  private final LedgerAccountRepository ledgerAccountRepository;
  private final LedgerBalanceQueryService ledgerBalanceQueryService;
  private final Clock clock;

  public WalletAccountService(
      WalletOwnerRepository walletOwnerRepository,
      LedgerAccountRepository ledgerAccountRepository,
      LedgerBalanceQueryService ledgerBalanceQueryService,
      Clock clock) {
    this.walletOwnerRepository = walletOwnerRepository;
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.ledgerBalanceQueryService = ledgerBalanceQueryService;
    this.clock = clock;
  }

  @Transactional
  public UUID createOwner(OwnerType ownerType) {
    return walletOwnerRepository.save(WalletOwner.create(ownerType, clock)).id();
  }

  @Transactional
  public WalletRegistration createWallet(OwnerType ownerType) {
    UUID ownerId = createOwner(ownerType);
    UUID walletAccountId = createWalletForOwner(ownerId);
    return new WalletRegistration(ownerId, walletAccountId);
  }

  @Transactional
  public UUID createWalletForOwner(UUID ownerId) {
    WalletOwner owner =
        walletOwnerRepository
            .findById(ownerId)
            .orElseThrow(() -> new IllegalArgumentException("wallet owner was not found"));
    if (!owner.isActive()) {
      throw new IllegalStateException("only active owners can receive a wallet");
    }
    return ledgerAccountRepository
        .save(LedgerAccount.wallet(owner.id(), CurrencyCode.USD, clock))
        .id();
  }

  @Transactional
  public UUID createSystemAccount(AccountType accountType, String systemCode) {
    return ledgerAccountRepository
        .save(LedgerAccount.system(accountType, systemCode, CurrencyCode.USD, clock))
        .id();
  }

  @Transactional
  public void suspendWallet(UUID walletAccountId) {
    LedgerAccount account =
        ledgerAccountRepository.lockAllByIdInOrder(java.util.List.of(walletAccountId)).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("wallet account was not found"));
    if (!account.isWallet()) {
      throw new IllegalArgumentException(
          "only wallet accounts can be suspended through this service");
    }
    account.suspend();
  }

  @Transactional
  public void closeWallet(UUID walletAccountId) {
    LedgerAccount account =
        ledgerAccountRepository.lockAllByIdInOrder(java.util.List.of(walletAccountId)).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("wallet account was not found"));
    if (!account.isWallet()) {
      throw new IllegalArgumentException("only wallet accounts can be closed through this service");
    }
    account.close(ledgerBalanceQueryService.balanceOf(account), clock);
  }
}
