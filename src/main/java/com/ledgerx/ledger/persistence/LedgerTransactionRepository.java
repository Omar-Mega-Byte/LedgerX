package com.ledgerx.ledger.persistence;

import com.ledgerx.ledger.domain.LedgerTransaction;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {}
