package com.ledgerx.transfer.application;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.transfer.domain.Transfer;
import com.ledgerx.transfer.domain.TransferNotFoundException;
import com.ledgerx.transfer.persistence.TransferRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferQueryService {

  private final TransferRepository transferRepository;
  private final LedgerAccountRepository ledgerAccountRepository;

  public TransferQueryService(
      TransferRepository transferRepository, LedgerAccountRepository ledgerAccountRepository) {
    this.transferRepository = transferRepository;
    this.ledgerAccountRepository = ledgerAccountRepository;
  }

  @Transactional(readOnly = true)
  public Transfer findForOwner(UUID transferId, OwnerContext ownerContext) {
    Transfer transfer =
        transferRepository
            .findById(transferId)
            .orElseThrow(() -> new TransferNotFoundException("transfer was not found"));
    LedgerAccount sourceWallet = findAccount(transfer.sourceWalletAccountId());
    LedgerAccount destinationWallet = findAccount(transfer.destinationWalletAccountId());
    if (!ownerContext.ownerId().equals(sourceWallet.ownerId())
        && !ownerContext.ownerId().equals(destinationWallet.ownerId())) {
      throw new TransferNotFoundException("transfer was not found");
    }
    return transfer;
  }

  private LedgerAccount findAccount(UUID accountId) {
    return ledgerAccountRepository
        .findById(accountId)
        .orElseThrow(() -> new TransferNotFoundException("transfer was not found"));
  }
}
