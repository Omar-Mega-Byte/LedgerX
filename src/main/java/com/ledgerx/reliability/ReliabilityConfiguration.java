package com.ledgerx.reliability;

import com.ledgerx.operations.ReconciliationProperties;
import com.ledgerx.webhook.WebhookProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
  OutboxProperties.class,
  KafkaProperties.class,
  WebhookProperties.class,
  ReconciliationProperties.class
})
public class ReliabilityConfiguration {}
