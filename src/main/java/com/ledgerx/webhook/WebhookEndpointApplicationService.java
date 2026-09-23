package com.ledgerx.webhook;

import com.ledgerx.access.OwnerContext;
import com.ledgerx.reliability.PaymentEventType;
import com.ledgerx.wallet.domain.OwnerType;
import com.ledgerx.wallet.domain.WalletOwner;
import com.ledgerx.wallet.persistence.WalletOwnerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merchant-owned webhook configuration with idempotent creation and redacted secret handling. */
@Service
public class WebhookEndpointApplicationService {

  private final WalletOwnerRepository walletOwnerRepository;
  private final WebhookEndpointStore endpointStore;
  private final WebhookDeliveryStore deliveryStore;
  private final WebhookSecretCipher secretCipher;
  private final WebhookUrlPolicy urlPolicy;
  private final WebhookProperties properties;
  private final Clock clock;

  public WebhookEndpointApplicationService(
      WalletOwnerRepository walletOwnerRepository,
      WebhookEndpointStore endpointStore,
      WebhookDeliveryStore deliveryStore,
      WebhookSecretCipher secretCipher,
      WebhookUrlPolicy urlPolicy,
      WebhookProperties properties,
      Clock clock) {
    this.walletOwnerRepository = walletOwnerRepository;
    this.endpointStore = endpointStore;
    this.deliveryStore = deliveryStore;
    this.secretCipher = secretCipher;
    this.urlPolicy = urlPolicy;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public WebhookEndpointExecution create(
      OwnerContext ownerContext,
      String targetUrl,
      Set<String> eventTypeNames,
      String signingSecret,
      String idempotencyKey) {
    UUID ownerId = ownerContext.ownerId();
    requireActiveMerchantForWrite(ownerId);
    String normalizedUrl = urlPolicy.normalize(targetUrl);
    Set<PaymentEventType> eventTypes = toEventTypes(eventTypeNames);
    String fingerprint = fingerprint(normalizedUrl, eventTypes, signingSecret);
    WebhookEndpointStore.EndpointClaim claim =
        endpointStore.claimCreation(ownerId, idempotencyKey, fingerprint);
    if (claim.isReplayed()) {
      return new WebhookEndpointExecution(claim.replayedEndpoint(), true);
    }
    if (endpointStore.countActiveForOwner(ownerId) >= properties.getMaxEndpointsPerMerchant()) {
      throw new WebhookValidationException(
          "merchant reached the configured webhook endpoint limit");
    }

    Instant now = clock.instant();
    WebhookEndpoint endpoint =
        new WebhookEndpoint(
            UUID.randomUUID(),
            ownerId,
            normalizedUrl,
            eventTypes,
            secretCipher.encrypt(signingSecret),
            properties.getEncryptionKeyVersion(),
            WebhookEndpointStatus.ACTIVE,
            now,
            now,
            null);
    endpointStore.insert(endpoint);
    endpointStore.completeCreation(claim.idempotencyId(), endpoint.id());
    return new WebhookEndpointExecution(endpoint, false);
  }

  @Transactional(readOnly = true)
  public List<WebhookEndpoint> list(OwnerContext ownerContext) {
    requireActiveMerchant(ownerContext.ownerId());
    return endpointStore.findAllForOwner(ownerContext.ownerId());
  }

  @Transactional(readOnly = true)
  public WebhookEndpoint find(OwnerContext ownerContext, UUID endpointId) {
    requireActiveMerchant(ownerContext.ownerId());
    return ownedEndpoint(ownerContext.ownerId(), endpointId);
  }

  @Transactional
  public WebhookEndpoint disable(OwnerContext ownerContext, UUID endpointId) {
    requireActiveMerchantForWrite(ownerContext.ownerId());
    ownedEndpoint(ownerContext.ownerId(), endpointId);
    endpointStore.disable(endpointId);
    return ownedEndpoint(ownerContext.ownerId(), endpointId);
  }

  @Transactional
  public WebhookEndpoint rotateSecret(
      OwnerContext ownerContext, UUID endpointId, String signingSecret) {
    requireActiveMerchantForWrite(ownerContext.ownerId());
    ownedEndpoint(ownerContext.ownerId(), endpointId);
    if (!endpointStore.rotateSecret(
        endpointId, secretCipher.encrypt(signingSecret), properties.getEncryptionKeyVersion())) {
      throw new WebhookDeliveryConflictException(
          "webhook endpoint cannot rotate its secret while disabled");
    }
    return ownedEndpoint(ownerContext.ownerId(), endpointId);
  }

  @Transactional(readOnly = true)
  public List<WebhookDelivery> listDeliveries(
      OwnerContext ownerContext, UUID endpointId, int limit) {
    requireActiveMerchant(ownerContext.ownerId());
    ownedEndpoint(ownerContext.ownerId(), endpointId);
    return deliveryStore.findAllForEndpointOwner(endpointId, ownerContext.ownerId(), limit);
  }

  @Transactional(readOnly = true)
  public WebhookDelivery findDelivery(OwnerContext ownerContext, UUID endpointId, UUID deliveryId) {
    requireActiveMerchant(ownerContext.ownerId());
    ownedEndpoint(ownerContext.ownerId(), endpointId);
    WebhookDelivery delivery =
        deliveryStore
            .findByIdForOwner(deliveryId, ownerContext.ownerId())
            .orElseThrow(() -> new WebhookNotFoundException("webhook delivery was not found"));
    if (!endpointId.equals(delivery.webhookEndpointId())) {
      throw new WebhookNotFoundException("webhook delivery was not found");
    }
    return delivery;
  }

  @Transactional
  public void replay(OwnerContext ownerContext, UUID endpointId, UUID deliveryId) {
    requireActiveMerchantForWrite(ownerContext.ownerId());
    WebhookEndpoint endpoint = ownedEndpoint(ownerContext.ownerId(), endpointId);
    if (endpoint.status() != WebhookEndpointStatus.ACTIVE) {
      throw new WebhookDeliveryConflictException(
          "disabled webhook endpoints cannot replay deliveries");
    }
    WebhookDelivery delivery =
        deliveryStore
            .findByIdForOwner(deliveryId, ownerContext.ownerId())
            .orElseThrow(() -> new WebhookNotFoundException("webhook delivery was not found"));
    if (!endpointId.equals(delivery.webhookEndpointId())) {
      throw new WebhookNotFoundException("webhook delivery was not found");
    }
    if (!deliveryStore.replay(deliveryId)) {
      throw new WebhookDeliveryConflictException(
          "only terminal webhook deliveries can be replayed");
    }
  }

  private void requireActiveMerchantForWrite(UUID ownerId) {
    List<WalletOwner> owners = walletOwnerRepository.lockAllByIdInOrder(List.of(ownerId));
    if (owners.size() != 1) {
      throw new WebhookAuthorizationException("caller is not an active merchant");
    }
    requireMerchant(owners.getFirst());
  }

  private void requireActiveMerchant(UUID ownerId) {
    WalletOwner owner =
        walletOwnerRepository
            .findById(ownerId)
            .orElseThrow(
                () -> new WebhookAuthorizationException("caller is not an active merchant"));
    requireMerchant(owner);
  }

  private void requireMerchant(WalletOwner owner) {
    if (!owner.isActive() || owner.ownerType() != OwnerType.MERCHANT) {
      throw new WebhookAuthorizationException("caller is not an active merchant");
    }
  }

  private WebhookEndpoint ownedEndpoint(UUID ownerId, UUID endpointId) {
    WebhookEndpoint endpoint =
        endpointStore
            .findById(endpointId)
            .orElseThrow(() -> new WebhookNotFoundException("webhook endpoint was not found"));
    if (!ownerId.equals(endpoint.ownerId())) {
      throw new WebhookNotFoundException("webhook endpoint was not found");
    }
    return endpoint;
  }

  private Set<PaymentEventType> toEventTypes(Set<String> eventTypeNames) {
    if (eventTypeNames == null || eventTypeNames.isEmpty()) {
      throw new WebhookValidationException("at least one webhook event type is required");
    }
    Set<PaymentEventType> eventTypes = EnumSet.noneOf(PaymentEventType.class);
    for (String eventTypeName : eventTypeNames) {
      try {
        eventTypes.add(PaymentEventType.fromWireName(eventTypeName));
      } catch (IllegalArgumentException exception) {
        throw new WebhookValidationException("unsupported webhook event type");
      }
    }
    return eventTypes;
  }

  private String fingerprint(
      String normalizedUrl, Set<PaymentEventType> eventTypes, String signingSecret) {
    if (signingSecret == null) {
      throw new WebhookValidationException("webhook signing secret is required");
    }
    return WebhookPayloadHash.sha256(
        normalizedUrl + "\n" + WebhookEndpoint.encodeEventTypes(eventTypes) + "\n" + signingSecret);
  }
}
