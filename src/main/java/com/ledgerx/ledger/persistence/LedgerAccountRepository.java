package com.ledgerx.ledger.persistence;

import com.ledgerx.ledger.domain.LedgerAccount;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT account FROM LedgerAccount account WHERE account.id IN :accountIds ORDER BY account.id")
  List<LedgerAccount> lockAllByIdInOrder(@Param("accountIds") Collection<UUID> accountIds);
}
