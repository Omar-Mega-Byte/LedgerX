package com.ledgerx.payment.domain;

public enum PaymentIdempotencyState {
  PROCESSING,
  REVIEW,
  BLOCKED,
  COMPLETED
}
