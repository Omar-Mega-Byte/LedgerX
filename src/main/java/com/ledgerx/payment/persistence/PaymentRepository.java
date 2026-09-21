package com.ledgerx.payment.persistence;

import com.ledgerx.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT payment FROM Payment payment WHERE payment.id = :paymentId")
  Optional<Payment> lockById(@Param("paymentId") UUID paymentId);
}
