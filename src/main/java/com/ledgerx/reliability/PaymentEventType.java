package com.ledgerx.reliability;

public enum PaymentEventType {
  PAYMENT_COMPLETED("payment.completed.v1"),
  REFUND_COMPLETED("refund.completed.v1");

  private final String wireName;

  PaymentEventType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static PaymentEventType fromWireName(String wireName) {
    for (PaymentEventType eventType : values()) {
      if (eventType.wireName.equals(wireName)) {
        return eventType;
      }
    }
    throw new IllegalArgumentException("unsupported payment event type");
  }
}
