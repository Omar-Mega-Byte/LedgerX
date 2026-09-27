package com.ledgerx.reliability;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Bounded consumer retries followed by a durable dead-letter topic. */
@Configuration
public class KafkaFailureConfiguration {

  @Bean
  @ConditionalOnExpression(
      "${ledgerx.outbox.publisher-enabled:false} || ${ledgerx.kafka.consumer-enabled:false} || ${ledgerx.webhooks.consumer-enabled:false}")
  NewTopic paymentEventsTopic(KafkaProperties properties) {
    return TopicBuilder.name(properties.getPaymentEventsTopic())
        .partitions(1)
        .replicas(1)
        .config(
            TopicConfig.RETENTION_MS_CONFIG,
            Long.toString(properties.getEventRetention().toMillis()))
        .build();
  }

  @Bean
  @ConditionalOnExpression(
      "${ledgerx.kafka.consumer-enabled:false} || ${ledgerx.webhooks.consumer-enabled:false}")
  NewTopic paymentEventsDeadLetterTopic(KafkaProperties properties) {
    return TopicBuilder.name(properties.getPaymentEventsTopic() + ".DLT")
        .partitions(1)
        .replicas(1)
        .config(
            TopicConfig.RETENTION_MS_CONFIG,
            Long.toString(properties.getDeadLetterRetention().toMillis()))
        .build();
  }

  @Bean
  CommonErrorHandler paymentEventErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate, KafkaProperties properties) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(properties.getPaymentEventsTopic() + ".DLT", -1));
    recoverer.setFailIfSendResultIsError(true);
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
  }
}
