package com.ledgerx.payment.application;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.payment.domain.Payment;
import com.ledgerx.payment.domain.PaymentAuthorizationException;
import com.ledgerx.payment.domain.PaymentCommand;
import com.ledgerx.payment.domain.PaymentIdempotencyFingerprint;
import com.ledgerx.payment.domain.PaymentIdempotencyKeyReuseException;
import com.ledgerx.payment.domain.PaymentIdempotencyRequestInProgressException;
import com.ledgerx.payment.domain.PaymentIdempotencyState;
import com.ledgerx.payment.domain.PaymentValidationException;
import com.ledgerx.payment.persistence.PaymentIdempotencyRecord;
import com.ledgerx.payment.persistence.PaymentIdempotencyStore;
import com.ledgerx.payment.persistence.PaymentRepository;
import com.ledgerx.reliability.PaymentOutboxService;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates payer-authorized merchant payments without bypassing the ledger. */
@Service
public class PaymentApplicationService {

  private final LedgerAccountRepository ledgerAccountRepository;
  private final WalletOwnerRepository walletOwnerRepository;
  private final LedgerPostingService ledgerPostingService;
  private final PaymentRepository paymentRepository;
  private final PaymentIdempotencyStore paymentIdempotencyStore;
  private final PaymentOutboxService paymentOutboxService;
  private final Clock clock;

  public PaymentApplicationService(
      LedgerAccountRepository ledgerAccountRepository,
      WalletOwnerRepository walletOwnerRepository,
      LedgerPostingService ledgerPostingService,
      PaymentRepository paymentRepository,
      PaymentIdempotencyStore paymentIdempotencyStore,
      PaymentOutboxService paymentOutboxService,
      Clock clock) {
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.walletOwnerRepository = walletOwnerRepository;
    this.ledgerPostingService = ledgerPostingService;
    this.paymentRepository = paymentRepository;
    this.paymentIdempotencyStore = paymentIdempotencyStore;
    this.paymentOutboxService = paymentOutboxService;
    this.clock = clock;
  }

  @Transactional
  public PaymentExecution create(OwnerContext ownerContext, PaymentCommand command) {
    requireCaller(ownerContext);
    String fingerprint = PaymentIdempotencyFingerprint.forPayment(command);
    UUID idempotencyId = UUID.randomUUID();
    Instant claimedAt = clock.instant();
    if (!paymentIdempotencyStore.claim(
        idempotencyId, ownerContext.ownerId(), command.idempotencyKey(), fingerprint, claimedAt)) {
      return resolveExisting(ownerContext, command, fingerprint);
    }

    LedgerAccount payerWallet = findWallet(command.payerWalletAccountId());
    LedgerAccount merchantWallet = findWallet(command.merchantWalletAccountId());
    validateCommand(ownerContext, command, payerWallet, merchantWallet);

    UUID paymentId = UUID.randomUUID();
    UUID ledgerTransactionId =
        ledgerPostingService.post(
            "Merchant payment " + paymentId,
            List.of(
                new PostingLine(payerWallet.id(), EntrySide.DEBIT, command.money()),
                new PostingLine(merchantWallet.id(), EntrySide.CREDIT, command.money())));
    Instant completedAt = clock.instant();
    Payment payment =
        Payment.completed(
            paymentId,
            payerWallet.id(),
            merchantWallet.id(),
            command.money(),
            ledgerTransactionId,
            completedAt);
    paymentRepository.saveAndFlush(payment);
    paymentOutboxService.recordPaymentCompleted(payment);
    paymentIdempotencyStore.complete(idempotencyId, payment.id(), completedAt);
    return PaymentExecution.created(payment);
  }

  private PaymentExecution resolveExisting(
      OwnerContext ownerContext, PaymentCommand command, String fingerprint) {
    PaymentIdempotencyRecord existing =
        paymentIdempotencyStore
            .find(ownerContext.ownerId(), command.idempotencyKey())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "payment idempotency conflict did not return a durable record"));
    if (!existing.requestFingerprint().equals(fingerprint)) {
      throw new PaymentIdempotencyKeyReuseException(
          "idempotency key was already used for a different payment request");
    }
    if (existing.state() == PaymentIdempotencyState.PROCESSING) {
      throw new PaymentIdempotencyRequestInProgressException(
          "payment idempotency request is still processing");
    }
    Payment payment =
        paymentRepository
            .findById(existing.resultId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "completed payment idempotency record has no durable payment"));
    return PaymentExecution.replayed(payment);
  }

  private void validateCommand(
      OwnerContext ownerContext,
      PaymentCommand command,
      LedgerAccount payerWallet,
      LedgerAccount merchantWallet) {
    if (!payerWallet.isWallet() || !merchantWallet.isWallet()) {
      throw new PaymentValidationException("payer and merchant accounts must be wallets");
    }
    if (payerWallet.id().equals(merchantWallet.id())) {
      throw new PaymentValidationException("payer and merchant wallets must differ");
    }
    if (!ownerContext.ownerId().equals(payerWallet.ownerId())) {
      throw new PaymentAuthorizationException("caller does not own the payer wallet");
    }
    if (payerWallet.currency() != command.money().currency()
        || merchantWallet.currency() != command.money().currency()) {
      throw new PaymentValidationException("wallet currencies must match the payment currency");
    }
    WalletOwner payerOwner = findOwner(payerWallet.ownerId());
    WalletOwner merchantOwner = findOwner(merchantWallet.ownerId());
    if (payerOwner.ownerType() != OwnerType.PERSON) {
      throw new PaymentValidationException("payer wallet must belong to a person");
    }
    if (merchantOwner.ownerType() != OwnerType.MERCHANT) {
      throw new PaymentValidationException("merchant wallet must belong to a merchant");
    }
  }

  private LedgerAccount findWallet(UUID walletAccountId) {
    return ledgerAccountRepository
        .findById(walletAccountId)
        .orElseThrow(() -> new PaymentValidationException("wallet account was not found"));
  }

  private void requireCaller(OwnerContext ownerContext) {
    if (!walletOwnerRepository.existsById(ownerContext.ownerId())) {
      throw new PaymentAuthorizationException("caller owner was not found");
    }
  }

  private WalletOwner findOwner(UUID ownerId) {
    return walletOwnerRepository
        .findById(ownerId)
        .orElseThrow(() -> new PaymentValidationException("wallet owner was not found"));
  }
}
