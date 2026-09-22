package com.ledgerx.access;

/** Raised when a validly authenticated caller cannot be mapped to a LedgerX wallet owner. */
public class OwnerIdentityException extends RuntimeException {

  public OwnerIdentityException(String message) {
    super(message);
  }
}
