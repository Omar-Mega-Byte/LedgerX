package com.ledgerx.reliability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, KafkaProperties.class})
public class ReliabilityConfiguration {}
