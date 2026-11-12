package io.github.laiwf94.payflow.api;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every error code the merchant API can answer with, and its HTTP status. This is the one list:
 * filters and the exception handler can only answer with one of these, and {@code
 * OpenApiContractTest} checks that openapi.yaml documents exactly these codes with these statuses.
 * Codes are part of the API contract and never change meaning; messages are for humans.
 */
public enum ApiError {
  INVALID_ARGUMENT(400, "invalid_argument"),
  INVALID_CURSOR(400, "invalid_cursor"),
  UNAUTHENTICATED(401, "unauthenticated"),
  NOT_FOUND(404, "not_found"),
  METHOD_NOT_ALLOWED(405, "method_not_allowed"),
  NOT_ACCEPTABLE(406, "not_acceptable"),
  ORDER_NOT_CANCELLABLE(409, "order_not_cancellable"),
  ORDER_NOT_REFUNDABLE(409, "order_not_refundable"),
  ORDER_REFERENCE_IN_USE(409, "order_reference_in_use"),
  REFUND_EXCEEDS_CAPTURED(409, "refund_exceeds_captured"),
  REFUND_EXCEEDS_PURCHASED(409, "refund_exceeds_purchased"),
  WEBHOOK_ENDPOINT_LIMIT_REACHED(409, "webhook_endpoint_limit_reached"),
  IDEMPOTENCY_KEY_IN_USE(409, "idempotency_key_in_use"),
  PAYLOAD_TOO_LARGE(413, "payload_too_large"),
  UNSUPPORTED_MEDIA_TYPE(415, "unsupported_media_type"),
  IDEMPOTENCY_KEY_REUSED(422, "idempotency_key_reused"),
  INTERNAL(500, "internal"),
  UNAVAILABLE(503, "unavailable");

  private static final Map<String, ApiError> BY_CODE =
      Arrays.stream(values())
          .collect(Collectors.toUnmodifiableMap(e -> e.code, Function.identity()));

  private final int status;
  private final String code;

  ApiError(int status, String code) {
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  /**
   * The error with this wire code, for exceptions raised outside the API package that carry their
   * code as a string.
   *
   * @throws IllegalArgumentException if no error has that code
   */
  public static ApiError ofCode(String code) {
    ApiError e = BY_CODE.get(code);
    if (e == null) {
      throw new IllegalArgumentException("no API error with code " + code);
    }
    return e;
  }
}
