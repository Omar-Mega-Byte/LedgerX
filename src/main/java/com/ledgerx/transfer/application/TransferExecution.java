package com.ledgerx.transfer.application;

import com.ledgerx.transfer.domain.Transfer;
import java.util.Objects;

/** Result of either a new completed transfer or a replay of its completed result. */
public record TransferExecution(Transfer transfer, boolean replayed) {

  public TransferExecution {
    Objects.requireNonNull(transfer, "transfer must not be null");
  }

  public static TransferExecution created(Transfer transfer) {
    return new TransferExecution(transfer, false);
  }

  public static TransferExecution replayed(Transfer transfer) {
    return new TransferExecution(transfer, true);
  }
}
