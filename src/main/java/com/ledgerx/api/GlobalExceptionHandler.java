package com.ledgerx.api;

import com.ledgerx.access.OwnerIdentityException;
import com.ledgerx.ledger.domain.FinancialValidationException;
import com.ledgerx.ledger.domain.LedgerTransactionNotFoundException;
import com.ledgerx.ledger.domain.UnknownLedgerAccountException;
import com.ledgerx.operations.OperationsConflictException;
import com.ledgerx.operations.OperationsNotFoundException;
import com.ledgerx.operations.OperationsValidationException;
import com.ledgerx.payment.domain.PaymentAuthorizationException;
import com.ledgerx.payment.domain.PaymentIdempotencyKeyReuseException;
import com.ledgerx.payment.domain.PaymentIdempotencyRequestInProgressException;
import com.ledgerx.payment.domain.PaymentNotFoundException;
import com.ledgerx.payment.domain.RefundAuthorizationException;
import com.ledgerx.risk.RiskConflictException;
import com.ledgerx.risk.RiskNotFoundException;
import com.ledgerx.risk.RiskValidationException;
import com.ledgerx.transfer.domain.IdempotencyKeyReuseException;
import com.ledgerx.transfer.domain.IdempotencyRequestInProgressException;
import com.ledgerx.transfer.domain.TransferAuthorizationException;
import com.ledgerx.transfer.domain.TransferNotFoundException;
import com.ledgerx.transfer.domain.WalletNotFoundException;
import com.ledgerx.webhook.WebhookAuthorizationException;
import com.ledgerx.webhook.WebhookDeliveryConflictException;
import com.ledgerx.webhook.WebhookIdempotencyKeyReuseException;
import com.ledgerx.webhook.WebhookIdempotencyRequestInProgressException;
import com.ledgerx.webhook.WebhookNotFoundException;
import com.ledgerx.webhook.WebhookValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<ApiFieldError> details =
        exception.getBindingResult().getFieldErrors().stream().map(this::toFieldError).toList();
    return error(
        HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request validation failed", request, details);
  }

  @ExceptionHandler({
    MalformedRequestException.class,
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

  @ExceptionHandler({TransferNotFoundException.class, PaymentNotFoundException.class})
  public ResponseEntity<ApiError> handleFinancialFactNotFound(
      Exception exception, HttpServletRequest request) {
    boolean refundPath = request.getRequestURI().startsWith("/api/v1/refunds");
    String code =
        refundPath
            ? "REFUND_NOT_FOUND"
            : isPaymentPath(request) ? "PAYMENT_NOT_FOUND" : "TRANSFER_NOT_FOUND";
    String message =
        refundPath
            ? "refund was not found"
            : isPaymentPath(request) ? "payment was not found" : "transfer was not found";
    return error(HttpStatus.NOT_FOUND, code, message, request, List.of());
  }

  @ExceptionHandler(WebhookNotFoundException.class)
  public ResponseEntity<ApiError> handleWebhookNotFound(
      WebhookNotFoundException exception, HttpServletRequest request) {
    return error(
        HttpStatus.NOT_FOUND,
        request.getRequestURI().contains("/deliveries/")
            ? "WEBHOOK_DELIVERY_NOT_FOUND"
            : "WEBHOOK_ENDPOINT_NOT_FOUND",
        "webhook resource was not found",
        request,
        List.of());
  }

  @ExceptionHandler(LedgerTransactionNotFoundException.class)
  public ResponseEntity<ApiError> handleLedgerNotFound(
      LedgerTransactionNotFoundException exception, HttpServletRequest request) {
    return error(
        HttpStatus.NOT_FOUND,
        "LEDGER_TRANSACTION_NOT_FOUND",
        "ledger transaction was not found",
        request,
        List.of());
  }

  @ExceptionHandler(OperationsNotFoundException.class)
  public ResponseEntity<ApiError> handleOperationsNotFound(
      OperationsNotFoundException exception, HttpServletRequest request) {
    return error(
        HttpStatus.NOT_FOUND, "OPERATIONS_NOT_FOUND", exception.getMessage(), request, List.of());
  }

  @ExceptionHandler(RiskNotFoundException.class)
  public ResponseEntity<ApiError> handleRiskNotFound(
      RiskNotFoundException exception, HttpServletRequest request) {
    return error(
        HttpStatus.NOT_FOUND,
        "RISK_CASE_NOT_FOUND",
        "risk review case was not found",
        request,
        List.of());
  }

  @ExceptionHandler(RiskConflictException.class)
  public ResponseEntity<ApiError> handleRiskConflict(
      RiskConflictException exception, HttpServletRequest request) {
    return error(HttpStatus.CONFLICT, "RISK_CONFLICT", exception.getMessage(), request, List.of());
  }

  @ExceptionHandler(RiskValidationException.class)
  public ResponseEntity<ApiError> handleRiskValidation(
      RiskValidationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "RISK_NOT_PROCESSABLE",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(OperationsValidationException.class)
  public ResponseEntity<ApiError> handleOperationsValidation(
      OperationsValidationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "OPERATIONS_NOT_PROCESSABLE",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(OperationsConflictException.class)
  public ResponseEntity<ApiError> handleOperationsConflict(
      OperationsConflictException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT, "OPERATIONS_CONFLICT", exception.getMessage(), request, List.of());
  }

  @ExceptionHandler(TransferAuthorizationException.class)
  public ResponseEntity<ApiError> handleTransferAuthorization(
      TransferAuthorizationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.FORBIDDEN,
        "TRANSFER_NOT_AUTHORIZED",
        "caller does not own the source wallet",
        request,
        List.of());
  }

  @ExceptionHandler(OwnerIdentityException.class)
  public ResponseEntity<ApiError> handleOwnerIdentity(
      OwnerIdentityException exception, HttpServletRequest request) {
    return error(
        HttpStatus.FORBIDDEN,
        "OWNER_IDENTITY_INVALID",
        "authenticated caller is not linked to a LedgerX wallet owner",
        request,
        List.of());
  }

  @ExceptionHandler(PaymentAuthorizationException.class)
  public ResponseEntity<ApiError> handlePaymentAuthorization(
      PaymentAuthorizationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.FORBIDDEN, "PAYMENT_NOT_AUTHORIZED", exception.getMessage(), request, List.of());
  }

  @ExceptionHandler(WebhookAuthorizationException.class)
  public ResponseEntity<ApiError> handleWebhookAuthorization(
      WebhookAuthorizationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.FORBIDDEN,
        "WEBHOOK_NOT_AUTHORIZED",
        "caller is not authorized to manage webhooks",
        request,
        List.of());
  }

  @ExceptionHandler(RefundAuthorizationException.class)
  public ResponseEntity<ApiError> handleRefundAuthorization(
      RefundAuthorizationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.FORBIDDEN, "REFUND_NOT_AUTHORIZED", exception.getMessage(), request, List.of());
  }

  @ExceptionHandler(FinancialValidationException.class)
  public ResponseEntity<ApiError> handleFinancialValidation(
      FinancialValidationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY,
        financialValidationCode(request),
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler(WebhookValidationException.class)
  public ResponseEntity<ApiError> handleWebhookValidation(
      WebhookValidationException exception, HttpServletRequest request) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "WEBHOOK_NOT_PROCESSABLE",
        exception.getMessage(),
        request,
        List.of());
  }

  @ExceptionHandler({
    IdempotencyKeyReuseException.class,
    PaymentIdempotencyKeyReuseException.class,
    WebhookIdempotencyKeyReuseException.class
  })
  public ResponseEntity<ApiError> handleIdempotencyReuse(
      Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "idempotency key was already used for a different request",
        request,
        List.of());
  }

  @ExceptionHandler({
    IdempotencyRequestInProgressException.class,
    PaymentIdempotencyRequestInProgressException.class,
    WebhookIdempotencyRequestInProgressException.class,
    WebhookDeliveryConflictException.class
  })
  public ResponseEntity<ApiError> handleIdempotencyInProgress(
      Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        exception instanceof WebhookDeliveryConflictException
            ? "WEBHOOK_DELIVERY_CONFLICT"
            : "IDEMPOTENCY_REQUEST_IN_PROGRESS",
        exception instanceof WebhookDeliveryConflictException
            ? exception.getMessage()
            : "idempotency request is still processing",
        request,
        List.of());
  }

  @ExceptionHandler(ConcurrencyFailureException.class)
  public ResponseEntity<ApiError> handleConcurrency(
      ConcurrencyFailureException exception, HttpServletRequest request) {
    String code =
        isPaymentPath(request) ? "CONCURRENT_PAYMENT_CONFLICT" : "CONCURRENT_TRANSFER_CONFLICT";
    return error(
        HttpStatus.CONFLICT,
        code,
        "concurrent financial command conflict; retry with the same idempotency key",
        request,
        List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    LOG.error("Unhandled request failure path={}", request.getRequestURI(), exception);
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "an unexpected error occurred",
        request,
        List.of());
  }

  private String financialValidationCode(HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/v1/demo/")) {
      return "DEMO_FUNDING_NOT_PROCESSABLE";
    }
    if (request.getRequestURI().contains("/refunds")) {
      return "REFUND_NOT_PROCESSABLE";
    }
    return isPaymentPath(request) ? "PAYMENT_NOT_PROCESSABLE" : "TRANSFER_NOT_PROCESSABLE";
  }

  private boolean isPaymentPath(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/v1/payments");
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
