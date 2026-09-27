package com.ledgerx.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtMutationRateLimitFilterTest {

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void limitsAuthenticatedPostRequestsWithoutBlockingReads() throws Exception {
    JwtMutationRateLimitFilter filter =
        new JwtMutationRateLimitFilter(
            Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC), 1);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("owner-1", "ignored", List.of()));

    MockHttpServletResponse first = responseFor(filter, "POST", "/api/v1/payments");
    MockHttpServletResponse second = responseFor(filter, "POST", "/api/v1/payments");
    MockHttpServletResponse read = responseFor(filter, "GET", "/api/v1/payments/id");

    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(second.getStatus()).isEqualTo(429);
    assertThat(second.getHeader("Retry-After")).isEqualTo("60");
    assertThat(read.getStatus()).isEqualTo(200);
  }

  private MockHttpServletResponse responseFor(
      JwtMutationRateLimitFilter filter, String method, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }
}
