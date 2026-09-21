package com.ledgerx.api;

public class MalformedRequestException extends RuntimeException {

  public MalformedRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
