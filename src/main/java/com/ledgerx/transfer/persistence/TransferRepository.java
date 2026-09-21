package com.ledgerx.transfer.persistence;

import com.ledgerx.transfer.domain.Transfer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {}
