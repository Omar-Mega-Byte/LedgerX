package com.ledgerx.payment.api;

import com.ledgerx.api.MoneyResponse;
import com.ledgerx.payment.application.PaymentView;
import com.ledgerx.payment.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An immutable merchant payment with its derived refund lifecycle state.")
public record PaymentResponse(
    @Schema(format = "uuid", example = "33333333-3333-3333-3333-333333333333") UUID paymentId,
    @Schema(format = "uuid", example = "11111111-1111-1111-1111-111111111111") UUID payerWalletId,
    @Schema(format = "uuid", example = "22222222-2222-2222-2222-222222222222")
        UUID merchantWalletId,
    MoneyResponse money,
    @Schema(example = "PARTIALLY_REFUNDED") PaymentStatus status,
    MoneyResponse refundedMoney,
    MoneyResponse remainingRefundableMoney,
    @Schema(format = "uuid", example = "44444444-4444-4444-4444-444444444444")
        UUID ledgerTransactionId,
    @Schema(format = "date-time", example = "2030-01-02T03:04:05Z") Instant completedAt) {

  public static PaymentResponse from(PaymentView view) {
    return new PaymentResponse(
        view.payment().id(),
        view.payment().payerWalletAccountId(),
        view.payment().merchantWalletAccountId(),
        MoneyResponse.from(view.payment().money()),
        view.status(),
        MoneyResponse.from(view.refundedMoney()),
        MoneyResponse.from(view.remainingRefundableMoney()),
        view.payment().ledgerTransactionId(),
        view.payment().completedAt());
  }
}
