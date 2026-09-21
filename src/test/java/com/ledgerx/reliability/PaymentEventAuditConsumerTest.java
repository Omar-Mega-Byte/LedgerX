package com.ledgerx.reliability;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentEventAuditConsumerTest {

  @Test
  void recordsTheValidatedEnvelopeUsingItsStableEventId() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    ProcessedEventStore processedEventStore = Mockito.mock(ProcessedEventStore.class);
    PaymentEventAuditConsumer consumer =
        new PaymentEventAuditConsumer(objectMapper, processedEventStore);
    PaymentEventEnvelope envelope =
        new PaymentEventEnvelope(
            UUID.randomUUID(),
            PaymentEventType.PAYMENT_COMPLETED.wireName(),
            UUID.randomUUID(),
            1,
            1,
            Instant.parse("2030-01-02T03:04:05Z"),
            Map.of("paymentId", UUID.randomUUID().toString()));
    String payload = objectMapper.writeValueAsString(envelope);

    consumer.consume(payload);

    verify(processedEventStore)
        .record(eq(PaymentEventAuditConsumer.CONSUMER_NAME), eq(envelope), eq(payload));
  }
}
