package com.ledgerx.wallet.persistence;

import com.ledgerx.wallet.domain.WalletOwner;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletOwnerRepository extends JpaRepository<WalletOwner, UUID> {}
