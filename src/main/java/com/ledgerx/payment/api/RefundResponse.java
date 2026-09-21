package com.ledgerx.payment.api;

import com.ledgerx.api.MoneyResponse;
import com.ledgerx.payment.domain.Refund;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An immutable compensating refund and its ledger journal.")
public record RefundResponse(
    @Schema(format = "uuid", example = "55555555-5555-5555-5555-555555555555") UUID refundId,
    @Schema(format = "uuid", example = "33333333-3333-3333-3333-333333333333") UUID paymentId,
    @Schema(format = "uuid", example = "22222222-2222-2222-2222-222222222222")
        UUID merchantWalletId,
    @Schema(format = "uuid", example = "11111111-1111-1111-1111-111111111111") UUID payerWalletId,
    MoneyResponse money,
    @Schema(format = "uuid", example = "66666666-6666-6666-6666-666666666666")
        UUID ledgerTransactionId,
    @Schema(format = "date-time", example = "2030-01-02T03:05:05Z") Instant completedAt) {

  public static RefundResponse from(Refund refund) {
    return new RefundResponse(
        refund.id(),
        refund.paymentId(),
        refund.merchantWalletAccountId(),
        refund.payerWalletAccountId(),
        MoneyResponse.from(refund.money()),
        refund.ledgerTransactionId(),
        refund.completedAt());
  }
}
