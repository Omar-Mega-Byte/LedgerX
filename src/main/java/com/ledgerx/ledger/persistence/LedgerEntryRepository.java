package com.ledgerx.ledger.persistence;

import com.ledgerx.ledger.domain.LedgerEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

  List<LedgerEntry> findAllByLedgerTransactionIdOrderByLineNumberAsc(UUID ledgerTransactionId);

  @Query(
      value =
          """
          SELECT COALESCE(
              SUM(CASE WHEN side = :normalSide THEN amount ELSE -amount END),
              CAST(0 AS NUMERIC)
          )
          FROM ledger_entries
          WHERE ledger_account_id = :accountId
          """,
      nativeQuery = true)
  BigDecimal calculateBalance(
      @Param("accountId") UUID accountId, @Param("normalSide") String normalSide);
}
