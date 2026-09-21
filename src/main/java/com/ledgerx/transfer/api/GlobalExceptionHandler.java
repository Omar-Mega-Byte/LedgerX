package com.ledgerx.transfer.api;

import com.ledgerx.ledger.domain.FinancialValidationException;
import com.ledgerx.ledger.domain.UnknownLedgerAccountException;
import com.ledgerx.transfer.domain.IdempotencyKeyReuseException;
import com.ledgerx.transfer.domain.IdempotencyRequestInProgressException;
import com.ledgerx.transfer.domain.TransferAuthorizationException;
import com.ledgerx.transfer.domain.TransferNotFoundException;
import com.ledgerx.transfer.domain.WalletNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<ApiFieldError> details =
        exception.getBindingResult().getFieldErrors().stream().map(this::toFieldError).toList();
    return error(
        HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request validation failed", request, details);
  }

  @ExceptionHandler({
    MalformedTransferRequestException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    ConstraintViolationException.class
  })
  public ResponseEntity<ApiError> handleMalformedRequest(
      Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.BAD_REQUEST,
        "MALFORMED_REQUEST",
        "request is malformed or missing a required value",
        request,
        List.of());
  }

  @ExceptionHandler({WalletNotFoundException.class, UnknownLedgerAccountException.class})
  public ResponseEntity<ApiError> handleWalletNotFound(
      Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "wallet was not found", request, List.of());
  }

  @ExceptionHandler(TransferNotFoundException.class)
  public ResponseEntity<ApiError> handleTransferNotFound(
      TransferNotFoundException exception, HttpServletRequest request) {
    return error(
        HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "transfer was not found", request, List.of());
  }

  @ExceptionHandler(TransferAuthorizationException.class)
  public ResponseEntity<ApiError> handleAuthorization(
      TransferAuthorizationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.FORBIDDEN,
        "TRANSFER_NOT_AUTHORIZED",
        "caller does not own the source wallet",
        request,
        List.of());
  }

  @ExceptionHandler(FinancialValidationException.class)
  public ResponseEntity<ApiError> handleFinancialValidation(
      FinancialValidationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "TRANSFER_NOT_PROCESSABLE",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(IdempotencyKeyReuseException.class)
  public ResponseEntity<ApiError> handleIdempotencyReuse(
      IdempotencyKeyReuseException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "idempotency key was already used for a different request",
        request,
        List.of());
  }

  @ExceptionHandler(IdempotencyRequestInProgressException.class)
  public ResponseEntity<ApiError> handleIdempotencyInProgress(
      IdempotencyRequestInProgressException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_REQUEST_IN_PROGRESS",
        "idempotency request is still processing",
        request,
        List.of());
  }

  @ExceptionHandler(ConcurrencyFailureException.class)
  public ResponseEntity<ApiError> handleConcurrency(
      ConcurrencyFailureException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "CONCURRENT_TRANSFER_CONFLICT",
        "concurrent transfer conflict; retry with the same idempotency key",
        request,
        List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "an unexpected error occurred",
        request,
        List.of());
  }

  private ApiFieldError toFieldError(FieldError fieldError) {
    return new ApiFieldError(fieldError.getField(), fieldError.getDefaultMessage());
  }

  private ResponseEntity<ApiError> error(
      HttpStatus status,
      String code,
      String message,
      HttpServletRequest request,
      List<ApiFieldError> details) {
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                Instant.now(), status.value(), code, message, request.getRequestURI(), details));
  }
}
