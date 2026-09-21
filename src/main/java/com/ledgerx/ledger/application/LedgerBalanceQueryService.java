package com.ledgerx.ledger.application;

import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.domain.UnknownLedgerAccountException;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.ledger.persistence.LedgerEntryRepository;
import com.ledgerx.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerBalanceQueryService {

  private final LedgerAccountRepository ledgerAccountRepository;
  private final LedgerEntryRepository ledgerEntryRepository;

  public LedgerBalanceQueryService(
      LedgerAccountRepository ledgerAccountRepository,
      LedgerEntryRepository ledgerEntryRepository) {
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
  }

  @Transactional(readOnly = true)
  public Money balanceOf(UUID ledgerAccountId) {
    LedgerAccount account =
        ledgerAccountRepository
            .findById(ledgerAccountId)
            .orElseThrow(
                () ->
                    new UnknownLedgerAccountException(
                        "ledger account " + ledgerAccountId + " was not found"));
    return balanceOf(account);
  }

  public Money balanceOf(LedgerAccount account) {
    BigDecimal amount =
        ledgerEntryRepository.calculateBalance(account.id(), account.normalSide().name());
    return new Money(amount, account.currency());
  }
}
