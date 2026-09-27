package com.ledgerx.payment.application;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.application.LedgerPostingService;
import com.ledgerx.ledger.domain.EntrySide;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.domain.PostingLine;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.money.Money;
import com.ledgerx.payment.domain.Payment;
import com.ledgerx.payment.domain.PaymentIdempotencyFingerprint;
import com.ledgerx.payment.domain.PaymentIdempotencyKeyReuseException;
import com.ledgerx.payment.domain.PaymentIdempotencyRequestInProgressException;
import com.ledgerx.payment.domain.PaymentIdempotencyState;
import com.ledgerx.payment.domain.PaymentNotFoundException;
import com.ledgerx.payment.domain.PaymentValidationException;
import com.ledgerx.payment.domain.Refund;
import com.ledgerx.payment.domain.RefundAuthorizationException;
import com.ledgerx.payment.domain.RefundCommand;
import com.ledgerx.payment.persistence.PaymentIdempotencyRecord;
import com.ledgerx.payment.persistence.PaymentRepository;
import com.ledgerx.payment.persistence.RefundIdempotencyStore;
import com.ledgerx.payment.persistence.RefundRepository;
import com.ledgerx.reliability.PaymentOutboxService;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates merchant-authorized compensating refunds under a payment-row lock. */
@Service
public class RefundApplicationService {

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final RefundIdempotencyStore refundIdempotencyStore;
  private final LedgerAccountRepository ledgerAccountRepository;
  private final WalletOwnerRepository walletOwnerRepository;
  private final LedgerPostingService ledgerPostingService;
  private final PaymentOutboxService paymentOutboxService;
  private final Clock clock;

  public RefundApplicationService(
      PaymentRepository paymentRepository,
      RefundRepository refundRepository,
      RefundIdempotencyStore refundIdempotencyStore,
      LedgerAccountRepository ledgerAccountRepository,
      WalletOwnerRepository walletOwnerRepository,
      LedgerPostingService ledgerPostingService,
      PaymentOutboxService paymentOutboxService,
      Clock clock) {
    this.paymentRepository = paymentRepository;
    this.refundRepository = refundRepository;
    this.refundIdempotencyStore = refundIdempotencyStore;
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.walletOwnerRepository = walletOwnerRepository;
    this.ledgerPostingService = ledgerPostingService;
    this.paymentOutboxService = paymentOutboxService;
    this.clock = clock;
  }

  @Transactional
  public RefundExecution create(OwnerContext ownerContext, RefundCommand command) {
    requireCaller(ownerContext);
    String fingerprint = PaymentIdempotencyFingerprint.forRefund(command);
    UUID idempotencyId = UUID.randomUUID();
    Instant claimedAt = clock.instant();
    if (!refundIdempotencyStore.claim(
        idempotencyId, ownerContext.ownerId(), command.idempotencyKey(), fingerprint, claimedAt)) {
      return resolveExisting(ownerContext, command, fingerprint);
    }

    Payment payment =
        paymentRepository
            .lockById(command.paymentId())
            .orElseThrow(() -> new PaymentNotFoundException("payment was not found"));
    LedgerAccount merchantWallet = findWallet(payment.merchantWalletAccountId());
    LedgerAccount payerWallet = findWallet(payment.payerWalletAccountId());
    validateRefundAuthorization(ownerContext, merchantWallet, payerWallet, command.money());
    validateRemainingAmount(payment, command.money());

    UUID refundId = UUID.randomUUID();
    UUID ledgerTransactionId =
        ledgerPostingService.post(
            "Payment refund " + refundId + " for payment " + payment.id(),
            List.of(
                new PostingLine(merchantWallet.id(), EntrySide.DEBIT, command.money()),
                new PostingLine(payerWallet.id(), EntrySide.CREDIT, command.money())));
    Instant completedAt = clock.instant();
    Refund refund =
        Refund.completed(
            refundId,
            payment.id(),
            merchantWallet.id(),
            payerWallet.id(),
            command.money(),
            ledgerTransactionId,
            completedAt);
    refundRepository.saveAndFlush(refund);
    paymentOutboxService.recordRefundCompleted(payment, refund);
    refundIdempotencyStore.complete(idempotencyId, refund.id(), completedAt);
    return RefundExecution.created(refund);
  }

  private RefundExecution resolveExisting(
      OwnerContext ownerContext, RefundCommand command, String fingerprint) {
    PaymentIdempotencyRecord existing =
        refundIdempotencyStore
            .find(ownerContext.ownerId(), command.idempotencyKey())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "refund idempotency conflict did not return a durable record"));
    if (!existing.requestFingerprint().equals(fingerprint)) {
      throw new PaymentIdempotencyKeyReuseException(
          "idempotency key was already used for a different refund request");
    }
    if (existing.state() == PaymentIdempotencyState.PROCESSING) {
      throw new PaymentIdempotencyRequestInProgressException(
          "refund idempotency request is still processing");
    }
    Refund refund =
        refundRepository
            .findById(existing.resultId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "completed refund idempotency record has no durable refund"));
    return RefundExecution.replayed(refund);
  }

  private void validateRefundAuthorization(
      OwnerContext ownerContext,
      LedgerAccount merchantWallet,
      LedgerAccount payerWallet,
      Money money) {
    if (!merchantWallet.isWallet() || !payerWallet.isWallet()) {
      throw new PaymentValidationException("payment participants must be wallets");
    }
    if (merchantWallet.currency() != money.currency()
        || payerWallet.currency() != money.currency()) {
      throw new PaymentValidationException("refund currency must match the payment wallets");
    }
    if (ownerContext.ownerId().equals(merchantWallet.ownerId())) {
      WalletOwner merchantOwner = findOwner(merchantWallet.ownerId());
      if (merchantOwner.ownerType() != OwnerType.MERCHANT) {
        throw new PaymentValidationException("payment merchant wallet must belong to a merchant");
      }
      return;
    }
    if (ownerContext.ownerId().equals(payerWallet.ownerId())) {
      throw new RefundAuthorizationException("only the payment merchant may create a refund");
    }
    throw new PaymentNotFoundException("payment was not found");
  }

  private void validateRemainingAmount(Payment payment, Money refundMoney) {
    if (payment.money().currency() != refundMoney.currency()) {
      throw new PaymentValidationException("refund currency must match the payment currency");
    }
    BigDecimal alreadyRefunded = refundRepository.totalAmountForPayment(payment.id());
    if (alreadyRefunded.add(refundMoney.amount()).compareTo(payment.money().amount()) > 0) {
      throw new PaymentValidationException("refund amount exceeds the remaining refundable amount");
    }
  }

  private LedgerAccount findWallet(UUID walletAccountId) {
    return ledgerAccountRepository
        .findById(walletAccountId)
        .orElseThrow(() -> new PaymentNotFoundException("payment was not found"));
  }

  private void requireCaller(OwnerContext ownerContext) {
    if (!walletOwnerRepository.existsById(ownerContext.ownerId())) {
      throw new PaymentNotFoundException("payment was not found");
    }
  }

  private WalletOwner findOwner(UUID ownerId) {
    return walletOwnerRepository
        .findById(ownerId)
        .orElseThrow(() -> new PaymentNotFoundException("payment was not found"));
  }
}
