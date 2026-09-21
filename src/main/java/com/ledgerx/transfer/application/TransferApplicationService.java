package com.ledgerx.transfer.application;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.transfer.domain.IdempotencyFingerprint;
import com.ledgerx.transfer.domain.IdempotencyKeyReuseException;
import com.ledgerx.transfer.domain.IdempotencyRequestInProgressException;
import com.ledgerx.transfer.domain.IdempotencyState;
import com.ledgerx.transfer.domain.Transfer;
import com.ledgerx.transfer.domain.TransferAuthorizationException;
import com.ledgerx.transfer.domain.TransferCommand;
import com.ledgerx.transfer.domain.TransferValidationException;
import com.ledgerx.transfer.domain.WalletNotFoundException;
import com.ledgerx.transfer.persistence.TransferIdempotencyRecord;
import com.ledgerx.transfer.persistence.TransferIdempotencyStore;
import com.ledgerx.transfer.persistence.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates transfer business facts while delegating all ledger posting to Phase 1. */
@Service
public class TransferApplicationService {

  private final LedgerAccountRepository ledgerAccountRepository;
  private final LedgerPostingService ledgerPostingService;
  private final TransferRepository transferRepository;
  private final TransferIdempotencyStore transferIdempotencyStore;
  private final Clock clock;

  public TransferApplicationService(
      LedgerAccountRepository ledgerAccountRepository,
      LedgerPostingService ledgerPostingService,
      TransferRepository transferRepository,
      TransferIdempotencyStore transferIdempotencyStore,
      Clock clock) {
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.ledgerPostingService = ledgerPostingService;
    this.transferRepository = transferRepository;
    this.transferIdempotencyStore = transferIdempotencyStore;
    this.clock = clock;
  }

  @Transactional
  public TransferExecution transfer(OwnerContext ownerContext, TransferCommand command) {
    String fingerprint = IdempotencyFingerprint.forCommand(command);
    UUID idempotencyId = UUID.randomUUID();
    Instant claimedAt = clock.instant();

    if (!transferIdempotencyStore.claim(
        idempotencyId, ownerContext.ownerId(), command.idempotencyKey(), fingerprint, claimedAt)) {
      return resolveExisting(ownerContext, command, fingerprint);
    }

    LedgerAccount sourceWallet = findWallet(command.sourceWalletAccountId());
    LedgerAccount destinationWallet = findWallet(command.destinationWalletAccountId());
    validateCommand(ownerContext, command, sourceWallet, destinationWallet);

    UUID transferId = UUID.randomUUID();
    UUID ledgerTransactionId =
        ledgerPostingService.post(
            "Wallet transfer " + transferId,
            List.of(
                new PostingLine(sourceWallet.id(), EntrySide.DEBIT, command.money()),
                new PostingLine(destinationWallet.id(), EntrySide.CREDIT, command.money())));
    Instant completedAt = clock.instant();
    Transfer transfer =
        Transfer.completed(
            transferId,
            sourceWallet.id(),
            destinationWallet.id(),
            command.money(),
            ledgerTransactionId,
            completedAt);
    transferRepository.saveAndFlush(transfer);
    transferIdempotencyStore.complete(idempotencyId, transfer.id(), completedAt);

    return TransferExecution.created(transfer);
  }

  private TransferExecution resolveExisting(
      OwnerContext ownerContext, TransferCommand command, String fingerprint) {
    TransferIdempotencyRecord existing =
        transferIdempotencyStore
            .find(ownerContext.ownerId(), command.idempotencyKey())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "idempotency key conflict did not return a durable record"));
    if (!existing.requestFingerprint().equals(fingerprint)) {
      throw new IdempotencyKeyReuseException(
          "idempotency key was already used for a different transfer request");
    }
    if (existing.state() == IdempotencyState.PROCESSING) {
      throw new IdempotencyRequestInProgressException("idempotency request is still processing");
    }
    Transfer transfer =
        transferRepository
            .findById(existing.transferId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "completed idempotency record has no durable transfer"));
    return TransferExecution.replayed(transfer);
  }

  private LedgerAccount findWallet(UUID walletAccountId) {
    return ledgerAccountRepository
        .findById(walletAccountId)
        .orElseThrow(() -> new WalletNotFoundException("wallet account was not found"));
  }

  private void validateCommand(
      OwnerContext ownerContext,
      TransferCommand command,
      LedgerAccount sourceWallet,
      LedgerAccount destinationWallet) {
    if (!sourceWallet.isWallet() || !destinationWallet.isWallet()) {
      throw new TransferValidationException("source and destination accounts must be wallets");
    }
    if (sourceWallet.id().equals(destinationWallet.id())) {
      throw new TransferValidationException("source and destination wallets must differ");
    }
    if (!ownerContext.ownerId().equals(sourceWallet.ownerId())) {
      throw new TransferAuthorizationException("caller does not own the source wallet");
    }
    if (sourceWallet.currency() != command.money().currency()
        || destinationWallet.currency() != command.money().currency()) {
      throw new TransferValidationException("wallet currencies must match the transfer currency");
    }
  }
}
