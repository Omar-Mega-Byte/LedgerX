package com.ledgerx.ledger.domain;

public enum AccountType {
  ASSET(EntrySide.DEBIT),
  LIABILITY(EntrySide.CREDIT),
  REVENUE(EntrySide.CREDIT),
  EXPENSE(EntrySide.DEBIT),
  EQUITY(EntrySide.CREDIT);

  private final EntrySide normalSide;

  AccountType(EntrySide normalSide) {
    this.normalSide = normalSide;
  }

  public EntrySide normalSide() {
    return normalSide;
  }
}
