package com.ledgerx.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Production-only resource-server boundary for public LedgerX traffic. */
@Configuration
@Profile("prod")
@EnableWebSecurity
public class ProductionSecurityConfiguration {

  @Bean
  SecurityFilterChain productionSecurityFilterChain(
      HttpSecurity http,
      Clock clock,
      @Value("${ledgerx.security.mutation-limit-per-minute:120}") int mutationLimit)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            sessionManagement ->
                sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorization ->
                authorization
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                    .hasRole("ledgerx-operator")
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/", "/index.html", "/ui/**", "/ui-config")
                    .permitAll()
                    .requestMatchers("/api/v1/demo/**")
                    .denyAll()
                    .requestMatchers("/api/v1/operations/**")
                    .hasRole("ledgerx-operator")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer.jwt(
                    jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
        .addFilterAfter(
            new JwtMutationRateLimitFilter(clock, mutationLimit),
            BearerTokenAuthenticationFilter.class)
        .build();
  }

  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Collection<org.springframework.security.core.GrantedAuthority> authorities =
              new ArrayList<>(scopes.convert(jwt));
          Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
          if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .forEach(authorities::add);
          }
          return authorities;
        });
    return converter;
  }
}
