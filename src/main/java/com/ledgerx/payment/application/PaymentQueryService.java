package com.ledgerx.payment.application;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.money.Money;
import com.ledgerx.payment.domain.Payment;
import com.ledgerx.payment.domain.PaymentNotFoundException;
import com.ledgerx.payment.domain.PaymentStatus;
import com.ledgerx.payment.persistence.PaymentRepository;
import com.ledgerx.payment.persistence.RefundRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentQueryService {

  private final PaymentRepository paymentRepository;
  private final RefundRepository refundRepository;
  private final LedgerAccountRepository ledgerAccountRepository;

  public PaymentQueryService(
      PaymentRepository paymentRepository,
      RefundRepository refundRepository,
      LedgerAccountRepository ledgerAccountRepository) {
    this.paymentRepository = paymentRepository;
    this.refundRepository = refundRepository;
    this.ledgerAccountRepository = ledgerAccountRepository;
  }

  @Transactional(readOnly = true)
  public PaymentView findForOwner(UUID paymentId, OwnerContext ownerContext) {
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException("payment was not found"));
    LedgerAccount payerWallet = findAccount(payment.payerWalletAccountId());
    LedgerAccount merchantWallet = findAccount(payment.merchantWalletAccountId());
    if (!ownerContext.ownerId().equals(payerWallet.ownerId())
        && !ownerContext.ownerId().equals(merchantWallet.ownerId())) {
      throw new PaymentNotFoundException("payment was not found");
    }
    Money refundedMoney =
        new Money(refundRepository.totalAmountForPayment(payment.id()), payment.money().currency());
    return new PaymentView(
        payment,
        PaymentStatus.from(payment.money(), refundedMoney),
        refundedMoney,
        payment.money().subtract(refundedMoney));
  }

  private LedgerAccount findAccount(UUID accountId) {
    return ledgerAccountRepository
        .findById(accountId)
        .orElseThrow(() -> new PaymentNotFoundException("payment was not found"));
  }
}
