package com.ledgerx.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.ledger.domain.LedgerAccount;
import com.ledgerx.ledger.persistence.LedgerAccountRepository;
import com.ledgerx.reliability.PaymentEventEnvelope;
import com.ledgerx.reliability.PaymentEventType;
import com.ledgerx.reliability.ProcessedEventStore;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts internal payment events into one durable public delivery instruction per eligible
 * endpoint.
 */
@Service
public class WebhookEventEnqueueService {

  public static final String CONSUMER_NAME = "webhook-delivery-enqueuer-v1";

  private final ObjectMapper objectMapper;
  private final ProcessedEventStore processedEventStore;
  private final LedgerAccountRepository ledgerAccountRepository;
  private final WalletOwnerRepository walletOwnerRepository;
  private final WebhookEndpointStore endpointStore;
  private final WebhookDeliveryStore deliveryStore;
  private final WebhookMetrics webhookMetrics;

  public WebhookEventEnqueueService(
      ObjectMapper objectMapper,
      ProcessedEventStore processedEventStore,
      LedgerAccountRepository ledgerAccountRepository,
      WalletOwnerRepository walletOwnerRepository,
      WebhookEndpointStore endpointStore,
      WebhookDeliveryStore deliveryStore,
      WebhookMetrics webhookMetrics) {
    this.objectMapper = objectMapper;
    this.processedEventStore = processedEventStore;
    this.ledgerAccountRepository = ledgerAccountRepository;
    this.walletOwnerRepository = walletOwnerRepository;
    this.endpointStore = endpointStore;
    this.deliveryStore = deliveryStore;
    this.webhookMetrics = webhookMetrics;
  }

  @Transactional
  public void enqueue(PaymentEventEnvelope envelope, String internalPayload) {
    if (!processedEventStore.record(CONSUMER_NAME, envelope, internalPayload)) {
      return;
    }
    PaymentEventType eventType = PaymentEventType.fromWireName(envelope.eventType());
    UUID merchantOwnerId = merchantOwnerId(envelope.data());
    String publicPayload = publicPayload(envelope, eventType);
    for (WebhookEndpoint endpoint :
        endpointStore.findActiveForOwnerAndEvent(merchantOwnerId, eventType)) {
      deliveryStore.enqueue(endpoint, envelope, publicPayload);
      webhookMetrics.queued(eventType);
    }
  }

  private UUID merchantOwnerId(Map<String, Object> data) {
    UUID merchantWalletId = uuid(data, "merchantWalletId");
    LedgerAccount merchantWallet =
        ledgerAccountRepository
            .findById(merchantWalletId)
            .orElseThrow(
                () -> new IllegalArgumentException("event merchant wallet does not exist"));
    if (!merchantWallet.isWallet() || merchantWallet.ownerId() == null) {
      throw new IllegalArgumentException("event merchant account is not an owned wallet");
    }
    WalletOwner merchantOwner =
        walletOwnerRepository
            .findById(merchantWallet.ownerId())
            .orElseThrow(() -> new IllegalArgumentException("event merchant owner does not exist"));
    if (merchantOwner.ownerType() != OwnerType.MERCHANT) {
      throw new IllegalArgumentException("event merchant wallet is not merchant owned");
    }
    return merchantOwner.id();
  }

  private String publicPayload(PaymentEventEnvelope envelope, PaymentEventType eventType) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put("paymentId", value(envelope.data(), "paymentId"));
    if (eventType == PaymentEventType.REFUND_COMPLETED) {
      data.put("refundId", value(envelope.data(), "refundId"));
      data.put("amount", value(envelope.data(), "refundAmount"));
    } else {
      data.put("amount", value(envelope.data(), "amount"));
    }
    data.put("currency", value(envelope.data(), "currency"));
    try {
      return objectMapper.writeValueAsString(
          new PublicWebhookEvent(
              envelope.eventId(), envelope.eventType(), "v1", envelope.occurredAt(), data));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("webhook payload could not be serialized", exception);
    }
  }

  private UUID uuid(Map<String, Object> data, String key) {
    try {
      return UUID.fromString(value(data, key));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("event " + key + " must be a UUID", exception);
    }
  }

  private String value(Map<String, Object> data, String key) {
    Object value = data.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new IllegalArgumentException("event " + key + " is required");
    }
    return value.toString();
  }
}
