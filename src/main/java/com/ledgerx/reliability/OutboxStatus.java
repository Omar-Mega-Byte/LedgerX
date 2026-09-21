package com.ledgerx.reliability;

public enum OutboxStatus {
  PENDING,
  IN_FLIGHT,
  PUBLISHED
}
