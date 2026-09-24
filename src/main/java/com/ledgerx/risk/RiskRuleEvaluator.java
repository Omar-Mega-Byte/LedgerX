package com.ledgerx.risk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Pure, ordered rule evaluation for one payment against a committed payer snapshot. */
final class RiskRuleEvaluator {

  private RiskRuleEvaluator() {}

  static Evaluation evaluate(
      BigDecimal amount,
      BigDecimal maxPaymentAmount,
      int completedCount,
      BigDecimal completedTotal,
      int reviewPaymentCount,
      BigDecimal reviewPaymentTotal,
      int openCases,
      int maxOpenCases,
      boolean approvedReview) {
    List<String> rules = new ArrayList<>();
    if (amount.compareTo(maxPaymentAmount) > 0) {
      rules.add("MAX_PAYMENT_AMOUNT");
    }
    if (openCases >= maxOpenCases && !approvedReview) {
      rules.add("OPEN_CASE_LIMIT");
    }
    if (!rules.isEmpty()) {
      return new Evaluation("BLOCK", String.join(",", rules));
    }
    if (!approvedReview) {
      if ((long) completedCount + 1 > reviewPaymentCount) {
        rules.add("PAYMENT_COUNT_24H");
      }
      if (completedTotal.add(amount).compareTo(reviewPaymentTotal) > 0) {
        rules.add("PAYMENT_TOTAL_24H");
      }
    }
    return new Evaluation(rules.isEmpty() ? "ALLOW" : "REVIEW", String.join(",", rules));
  }

  record Evaluation(String outcome, String ruleCodes) {}
}
