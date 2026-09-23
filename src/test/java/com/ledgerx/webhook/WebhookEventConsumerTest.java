package com.ledgerx.webhook;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerx.reliability.PaymentEventEnvelope;
import com.ledgerx.reliability.PaymentEventType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WebhookEventConsumerTest {

  @Test
  void delegatesTheValidatedInternalPayloadToTheTransactionalEnqueuer() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    WebhookEventEnqueueService enqueueService = Mockito.mock(WebhookEventEnqueueService.class);
    WebhookEventConsumer consumer = new WebhookEventConsumer(objectMapper, enqueueService);
    PaymentEventEnvelope envelope =
        new PaymentEventEnvelope(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_COMPLETED.wireName(),
            UUID.randomUUID(),
            1,
            1,
            Instant.parse("2030-01-02T03:04:05Z"),
            Map.of("merchantWalletId", UUID.randomUUID().toString()));
    String payload = objectMapper.writeValueAsString(envelope);

    consumer.consume(payload);

    verify(enqueueService).enqueue(eq(envelope), eq(payload));
  }
}
