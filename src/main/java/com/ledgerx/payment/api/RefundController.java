package com.ledgerx.payment.api;

import com.ledgerx.access.OwnerContextResolver;
import com.ledgerx.payment.application.PaymentQueryService;
import com.ledgerx.payment.domain.PaymentNotFoundException;
import com.ledgerx.payment.domain.Refund;
import com.ledgerx.payment.persistence.RefundRepository;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped refund lookup for durable links and activity records. */
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {

  private final RefundRepository refundRepository;
  private final PaymentQueryService paymentQueryService;
  private final OwnerContextResolver ownerContextResolver;

  public RefundController(
      RefundRepository refundRepository,
      PaymentQueryService paymentQueryService,
      OwnerContextResolver ownerContextResolver) {
    this.refundRepository = refundRepository;
    this.paymentQueryService = paymentQueryService;
    this.ownerContextResolver = ownerContextResolver;
  }

  @GetMapping("/{refundId}")
  public RefundResponse find(
      @RequestHeader(value = "X-LedgerX-Owner-Id", required = false) String ownerHeader,
      @AuthenticationPrincipal Jwt authenticatedToken,
      @PathVariable UUID refundId) {
    Refund refund =
        refundRepository
            .findById(refundId)
            .orElseThrow(() -> new PaymentNotFoundException("refund was not found"));
    paymentQueryService.findForOwner(
        refund.paymentId(), ownerContextResolver.resolve(ownerHeader, authenticatedToken));
    return RefundResponse.from(refund);
  }
}
