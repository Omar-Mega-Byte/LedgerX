package com.ledgerx.transfer.api;

class MalformedTransferRequestException extends RuntimeException {

  MalformedTransferRequestException(String message, Throwable cause) {
    super(message, cause);
  }

  MalformedTransferRequestException(String message) {
    super(message);
  }
}
