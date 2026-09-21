package com.ledgerx.transfer.api;

import com.ledgerx.transfer.domain.Transfer;
import com.ledgerx.transfer.domain.TransferStatus;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
    UUID transferId,
    UUID sourceWalletId,
    UUID destinationWalletId,
    MoneyResponse money,
    TransferStatus status,
    UUID ledgerTransactionId,
    Instant completedAt) {

  public static TransferResponse from(Transfer transfer) {
    return new TransferResponse(
        transfer.id(),
        transfer.sourceWalletAccountId(),
        transfer.destinationWalletAccountId(),
        MoneyResponse.from(transfer.money()),
        transfer.status(),
        transfer.ledgerTransactionId(),
        transfer.completedAt());
  }
}
