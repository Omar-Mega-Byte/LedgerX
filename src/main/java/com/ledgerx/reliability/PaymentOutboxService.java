package com.ledgerx.reliability;

import com.ledgerx.payment.domain.Payment;
import com.ledgerx.payment.domain.Refund;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Creates immutable v1 outbox facts inside payment/refund command transactions. */
@Service
public class PaymentOutboxService {

  private final OutboxEventStore outboxEventStore;

  public PaymentOutboxService(OutboxEventStore outboxEventStore) {
    this.outboxEventStore = outboxEventStore;
  }

  public void recordPaymentCompleted(Payment payment) {
    append(
        PaymentEventType.PAYMENT_COMPLETED,
        payment.id(),
        payment.completedAt(),
        paymentData(payment));
  }

  public void recordRefundCompleted(Payment payment, Refund refund) {
    Map<String, Object> data = new LinkedHashMap<>(paymentData(payment));
    data.put("refundId", refund.id().toString());
    data.put("refundLedgerTransactionId", refund.ledgerTransactionId().toString());
    data.put("refundAmount", refund.money().amount().toPlainString());
    append(PaymentEventType.REFUND_COMPLETED, payment.id(), refund.completedAt(), data);
  }

  private void append(
      PaymentEventType eventType,
      UUID paymentId,
      java.time.Instant occurredAt,
      Map<String, Object> data) {
    long sequence = outboxEventStore.nextAggregateSequence(paymentId);
    outboxEventStore.append(
        new PaymentEventEnvelope(
            UUID.randomUUID(), eventType.wireName(), paymentId, sequence, 1, occurredAt, data));
  }

  private Map<String, Object> paymentData(Payment payment) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paymentId", payment.id().toString());
    data.put("payerWalletId", payment.payerWalletAccountId().toString());
    data.put("merchantWalletId", payment.merchantWalletAccountId().toString());
    data.put("amount", payment.money().amount().toPlainString());
    data.put("currency", payment.money().currency().name());
    data.put("ledgerTransactionId", payment.ledgerTransactionId().toString());
    return data;
  }
}
