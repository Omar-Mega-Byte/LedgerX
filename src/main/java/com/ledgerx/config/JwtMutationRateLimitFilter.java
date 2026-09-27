package com.ledgerx.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single-instance abuse limit; financial idempotency remains the correctness boundary. */
final class JwtMutationRateLimitFilter extends OncePerRequestFilter {

  private final Clock clock;
  private final int requestsPerMinute;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final AtomicLong lastPrunedMinute = new AtomicLong();

  JwtMutationRateLimitFilter(Clock clock, int requestsPerMinute) {
    if (requestsPerMinute <= 0) {
      throw new IllegalArgumentException("mutation rate limit must be positive");
    }
    this.clock = clock;
    this.requestsPerMinute = requestsPerMinute;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      chain.doFilter(request, response);
      return;
    }
    long second = clock.instant().getEpochSecond();
    long minute = second / 60;
    pruneOldWindows(minute);
    String subject = authentication.getName();
    if (!StringUtils.hasText(subject)) {
      subject = "authenticated-without-subject";
    }
    if (!windows.containsKey(subject) && windows.size() >= 50_000) {
      response.setStatus(429);
      response.setHeader("Retry-After", Long.toString(60 - (second % 60)));
      return;
    }
    Window window =
        windows.compute(
            subject,
            (name, previous) ->
                previous == null || previous.minute() != minute
                    ? new Window(minute, 1)
                    : new Window(minute, previous.count() + 1));
    if (window.count() > requestsPerMinute) {
      response.setStatus(429);
      response.setHeader("Retry-After", Long.toString(60 - (second % 60)));
      response.setHeader("Cache-Control", "no-store");
      return;
    }
    chain.doFilter(request, response);
  }

  private void pruneOldWindows(long minute) {
    long prior = lastPrunedMinute.get();
    if (prior < minute && lastPrunedMinute.compareAndSet(prior, minute)) {
      windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
    }
  }

  private record Window(long minute, int count) {}
}
