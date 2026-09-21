package com.ledgerx.ledger.persistence;

import com.ledgerx.ledger.domain.LedgerAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {}
