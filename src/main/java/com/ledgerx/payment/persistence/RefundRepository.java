package com.ledgerx.payment.persistence;

import com.ledgerx.payment.domain.Refund;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

  @Query(
      value =
          "SELECT COALESCE(SUM(amount), CAST(0 AS NUMERIC)) FROM refunds WHERE payment_id = :paymentId",
      nativeQuery = true)
  BigDecimal totalAmountForPayment(@Param("paymentId") UUID paymentId);
}
