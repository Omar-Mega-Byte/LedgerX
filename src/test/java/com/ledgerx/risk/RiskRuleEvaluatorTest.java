package com.ledgerx.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RiskRuleEvaluatorTest {

  @Test
  void exactThresholdsAllowButCrossingBothVelocityThresholdsReviews() {
    RiskRuleEvaluator.Evaluation atThreshold = evaluate("10.00", 0, "0.00", 0, false);
    RiskRuleEvaluator.Evaluation overThreshold = evaluate("10.01", 1, "10.00", 0, false);

    assertThat(atThreshold.outcome()).isEqualTo("ALLOW");
    assertThat(atThreshold.ruleCodes()).isEmpty();
    assertThat(overThreshold.outcome()).isEqualTo("REVIEW");
    assertThat(overThreshold.ruleCodes()).isEqualTo("PAYMENT_COUNT_24H,PAYMENT_TOTAL_24H");
  }

  @Test
  void hardBlockPrecedesReviewAndApprovalCannotBypassIt() {
    RiskRuleEvaluator.Evaluation initial = evaluate("100.01", 1, "10.00", 10, false);
    RiskRuleEvaluator.Evaluation approved = evaluate("100.01", 1, "10.00", 10, true);

    assertThat(initial.outcome()).isEqualTo("BLOCK");
    assertThat(initial.ruleCodes()).isEqualTo("MAX_PAYMENT_AMOUNT,OPEN_CASE_LIMIT");
    assertThat(approved.outcome()).isEqualTo("BLOCK");
    assertThat(approved.ruleCodes()).isEqualTo("MAX_PAYMENT_AMOUNT");
  }

  @Test
  void validApprovalBypassesVelocityAndQueueRules() {
    RiskRuleEvaluator.Evaluation evaluation = evaluate("10.00", 2, "50.00", 10, true);

    assertThat(evaluation.outcome()).isEqualTo("ALLOW");
    assertThat(evaluation.ruleCodes()).isEmpty();
  }

  private RiskRuleEvaluator.Evaluation evaluate(
      String amount, int count, String total, int openCases, boolean approvedReview) {
    return RiskRuleEvaluator.evaluate(
        new BigDecimal(amount),
        new BigDecimal("100.00"),
        count,
        new BigDecimal(total),
        1,
        new BigDecimal("10.00"),
        openCases,
        10,
        approvedReview);
  }
}
