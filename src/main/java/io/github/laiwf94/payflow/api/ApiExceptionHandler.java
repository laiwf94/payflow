package io.github.laiwf94.payflow.api;

import io.github.laiwf94.payflow.money.InvalidMoneyException;
import io.github.laiwf94.payflow.order.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    return error(400, "invalid_argument", message, req);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest req) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BodyLimitFilter.PayloadTooLarge) {
        return error(413, "payload_too_large", t.getMessage(), req);
      }
    }
    return error(
        400,
        "invalid_argument",
        "body must be a JSON object with the documented fields and types",
        req);
  }

  @ExceptionHandler({InvalidMoneyException.class, IllegalArgumentException.class})
  ResponseEntity<Map<String, Object>> badValue(RuntimeException e, HttpServletRequest req) {
    return error(400, "invalid_argument", e.getMessage(), req);
  }

  @ExceptionHandler({OrderService.NotFound.class, OrderController.OrderNotFound.class})
  ResponseEntity<Map<String, Object>> notFound(RuntimeException e, HttpServletRequest req) {
    return error(404, "not_found", "order not found", req);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e, HttpServletRequest req) {
    return error(404, "not_found", "no such endpoint", req);
  }

  @ExceptionHandler(OrderService.WrongState.class)
  ResponseEntity<Map<String, Object>> wrongState(
      OrderService.WrongState e, HttpServletRequest req) {
    return error(409, e.code(), e.getMessage(), req);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, Object>> method(
      HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
    return error(405, "method_not_allowed", "method not allowed here", req);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Map<String, Object>> mediaType(
      HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
    return error(415, "unsupported_media_type", "send Content-Type: application/json", req);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<Map<String, Object>> notAcceptable(
      HttpMediaTypeNotAcceptableException e, HttpServletRequest req) {
    return error(406, "not_acceptable", "responses are application/json", req);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
    log.error("unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
    return error(500, "internal", "internal error", req);
  }

  static String snake(String field) {
    return CAMEL.matcher(field).replaceAll("$1_$2").toLowerCase(java.util.Locale.ROOT);
  }

  private static ResponseEntity<Map<String, Object>> error(
      int status, String code, String message, HttpServletRequest req) {
    return ResponseEntity.status(status).body(ApiErrors.body(code, message, RequestIds.of(req)));
  }
}
