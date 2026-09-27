package com.ledgerx.operations.api;

import com.ledgerx.reliability.OutboxReplayService;
import com.ledgerx.reliability.OutboxReplayService.ReplayRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator-only external replay; the existing production filter chain guards this route. */
@RestController
@RequestMapping("/api/v1/operations/outbox-events")
public class OutboxReplayController {

  private final OutboxReplayService replayService;

  public OutboxReplayController(OutboxReplayService replayService) {
    this.replayService = replayService;
  }

  @PostMapping("/{eventId}/replay")
  public ResponseEntity<ReplayRequest> replay(
      @PathVariable UUID eventId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ReplayBody body,
      @AuthenticationPrincipal Jwt token) {
    ReplayRequest result =
        replayService.requestReplay(
            eventId,
            idempotencyKey,
            body.reason(),
            token == null ? "local-operator" : token.getSubject());
    return ResponseEntity.accepted().body(result);
  }

  public record ReplayBody(String reason) {}
}
