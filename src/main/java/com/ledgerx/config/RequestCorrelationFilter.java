package com.ledgerx.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Echoes a bounded request ID and places it in structured logs for this request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String requestId =
        supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    response.setHeader(HEADER, requestId);
    MDC.put("request.id", requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("request.id");
    }
  }
}
