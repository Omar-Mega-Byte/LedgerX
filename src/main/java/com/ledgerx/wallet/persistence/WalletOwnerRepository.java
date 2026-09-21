package com.ledgerx.wallet.persistence;

import com.ledgerx.wallet.domain.WalletOwner;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletOwnerRepository extends JpaRepository<WalletOwner, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT owner FROM WalletOwner owner WHERE owner.id IN :ownerIds ORDER BY owner.id")
  List<WalletOwner> lockAllByIdInOrder(@Param("ownerIds") Collection<UUID> ownerIds);
}
