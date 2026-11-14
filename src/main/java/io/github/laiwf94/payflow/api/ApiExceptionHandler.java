package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.money.InvalidMoneyException;
import io.github.laiwf94.payflow.order.OrderService;
import io.github.laiwf94.payflow.refund.RefundService;
import io.github.laiwf94.payflow.webhook.WebhookDeliveryLog;
import io.github.laiwf94.payflow.webhook.WebhookEndpointService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to the standard error body. It covers requests that never reach a controller
 * (unknown paths, wrong methods), so it isn't limited to the API package; the simulators' own
 * handler runs first for their routes.
 */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> invalid(
      MethodArgumentNotValidException e, HttpServletRequest req) {
    // Report one field, chosen deterministically, so the same bad request gets the same answer.
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .min(Comparator.comparing(FieldError::getField))
            .map(f -> snake(f.getField()) + ": " + f.getDefaultMessage())
            .orElse("invalid request");
    return error(ApiError.INVALID_ARGUMENT, message, req);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest req) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BodyLimitFilter.PayloadTooLarge) {
        return error(ApiError.PAYLOAD_TOO_LARGE, t.getMessage(), req);
      }
    }
    return error(
        ApiError.INVALID_ARGUMENT,
        "body must be a JSON object with the documented fields and types",
        req);
  }

  @ExceptionHandler({InvalidMoneyException.class, IllegalArgumentException.class})
  ResponseEntity<Map<String, Object>> badValue(RuntimeException e, HttpServletRequest req) {
    return error(ApiError.INVALID_ARGUMENT, e.getMessage(), req);
  }

  @ExceptionHandler(ListCursors.InvalidCursor.class)
  ResponseEntity<Map<String, Object>> invalidCursor(
      ListCursors.InvalidCursor e, HttpServletRequest req) {
    return error(ApiError.INVALID_CURSOR, e.getMessage(), req);
  }

  @ExceptionHandler({OrderService.NotFound.class, OrderController.OrderNotFound.class})
  ResponseEntity<Map<String, Object>> notFound(RuntimeException e, HttpServletRequest req) {
    return error(ApiError.NOT_FOUND, "order not found", req);
  }

  @ExceptionHandler(WebhookEndpointService.NotFound.class)
  ResponseEntity<Map<String, Object>> webhookEndpointNotFound(
      WebhookEndpointService.NotFound e, HttpServletRequest req) {
    return error(ApiError.NOT_FOUND, e.getMessage(), req);
  }

  @ExceptionHandler(WebhookDeliveryLog.DeliveryNotFound.class)
  ResponseEntity<Map<String, Object>> webhookDeliveryNotFound(
      WebhookDeliveryLog.DeliveryNotFound e, HttpServletRequest req) {
    return error(ApiError.NOT_FOUND, e.getMessage(), req);
  }

  @ExceptionHandler(WebhookEndpointService.LimitReached.class)
  ResponseEntity<Map<String, Object>> webhookEndpointLimit(
      WebhookEndpointService.LimitReached e, HttpServletRequest req) {
    return error(ApiError.WEBHOOK_ENDPOINT_LIMIT_REACHED, e.getMessage(), req);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e, HttpServletRequest req) {
    return error(ApiError.NOT_FOUND, "no such endpoint", req);
  }

  @ExceptionHandler(OrderService.WrongState.class)
  ResponseEntity<Map<String, Object>> wrongState(
      OrderService.WrongState e, HttpServletRequest req) {
    return error(ApiError.ofCode(e.code()), e.getMessage(), req);
  }

  @ExceptionHandler(OrderService.ReferenceInUse.class)
  ResponseEntity<Map<String, Object>> referenceInUse(
      OrderService.ReferenceInUse e, HttpServletRequest req) {
    return error(ApiError.ORDER_REFERENCE_IN_USE, e.getMessage(), req);
  }

  @ExceptionHandler(RefundService.ExceedsCaptured.class)
  ResponseEntity<Map<String, Object>> refundExceedsCaptured(
      RefundService.ExceedsCaptured e, HttpServletRequest req) {
    return error(ApiError.REFUND_EXCEEDS_CAPTURED, e.getMessage(), req);
  }

  @ExceptionHandler(RefundService.ExceedsPurchased.class)
  ResponseEntity<Map<String, Object>> refundExceedsPurchased(
      RefundService.ExceedsPurchased e, HttpServletRequest req) {
    return error(ApiError.REFUND_EXCEEDS_PURCHASED, e.getMessage(), req);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, Object>> method(
      HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
    return error(ApiError.METHOD_NOT_ALLOWED, "method not allowed here", req);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType(
      HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
    return error(ApiError.UNSUPPORTED_MEDIA_TYPE, "send Content-Type: application/json", req);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<Map<String, Object>> notAcceptable(
      HttpMediaTypeNotAcceptableException e, HttpServletRequest req) {
    return error(ApiError.NOT_ACCEPTABLE, "responses are application/json", req);
  }

  /**
   * The database can't be reached: refused, the pool timed out, or the connection dropped. Nothing
   * was committed (a POST's transaction rolls back on any 5xx), so the client should retry.
   */
  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    TransientDataAccessResourceException.class,
    RecoverableDataAccessException.class
  })
  ResponseEntity<Map<String, Object>> databaseUnreachable(
      DataAccessException e, HttpServletRequest req) {
    log.warn(
        "database unreachable on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
    return error(ApiError.UNAVAILABLE, "temporarily unavailable; retry", req);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
    log.error("unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
    return error(ApiError.INTERNAL, "internal error", req);
  }

  static String snake(String field) {
    return CAMEL.matcher(field).replaceAll("$1_$2").toLowerCase(java.util.Locale.ROOT);
  }

  private static ResponseEntity<Map<String, Object>> error(
      ApiError error, String message, HttpServletRequest req) {
    // The content type is set, not negotiated: an error is JSON even when the Accept header
    // asked for something else, which is exactly the case a 406 reports.
    return ResponseEntity.status(error.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiErrors.body(error, message, RequestIds.of(req)));
  }
}
