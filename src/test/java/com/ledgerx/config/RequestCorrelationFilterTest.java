package com.ledgerx.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

  private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

  @Test
  void preservesSafeClientIdInResponseAndLogContextWithoutLeakingIt() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "incident-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) ->
            assertThat(MDC.get("request.id")).isEqualTo("incident-123"));

    assertThat(response.getHeader("X-Request-Id")).isEqualTo("incident-123");
    assertThat(MDC.get("request.id")).isNull();
  }

  @Test
  void replacesUntrustedHeaderContent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "untrusted\nline");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});

    assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    assertThat(MDC.get("request.id")).isNull();
  }
}
