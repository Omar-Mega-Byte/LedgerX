package com.ledgerx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Keeps the explicit development ownership seam available to local and Testcontainers workflows.
 * Production authentication is configured separately in {@link ProductionSecurityConfiguration}.
 */
@Configuration
@Profile("!prod")
public class DevelopmentSecurityConfiguration {

  @Bean
  SecurityFilterChain developmentSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorization -> authorization.anyRequest().permitAll())
        .build();
  }
}
