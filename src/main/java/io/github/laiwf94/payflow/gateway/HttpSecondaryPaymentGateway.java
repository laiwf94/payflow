package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * {@link PaymentGateway} over the second provider's HTTP API, which speaks a dialect of its own:
 * camelCase bodies, amounts as {@code {"value", "currency"}}, payments and refunds known by a
 * {@code pspReference}, and modifications posted to sub-resources of the payment.
 *
 * <p>The adapter's job is to make that provider read exactly like the first one to the saga:
 *
 * <ul>
 *   <li>A refused authorization comes back as a 200 with {@code "resultCode": "Refused"} and a
 *       numeric {@code refusalReasonCode}; it becomes {@link CallResult.Rejected} with the same
 *       decline code the first provider sends ({@link #DECLINE_CODES}).
 *   <li>Errors are {@code {"status", "errorCode", "errorType", "message"}} with a numeric code; the
 *       ones the steps act on ({@code authorization_expired}, {@code already_captured} and the rest
 *       of {@link #ERROR_CODES}) are translated, and any other is kept as {@code secondary_<code>}
 *       so it still says where it came from.
 *   <li>Its statuses ({@code authorised}, {@code refused}, {@code captured}, {@code cancelled},
 *       {@code expired}) become {@link PaymentStatus}; one it has never sent is an unexpected body,
 *       which the step retries rather than guesses at.
 * </ul>
 *
 * <p>Timeouts, 5xx, 503 and 429 are classified by {@link HttpDependency} exactly as for the first
 * provider, so the rules for unknown outcomes don't depend on the provider.
 */
final class HttpSecondaryPaymentGateway implements PaymentGateway {

  /** Its {@code refusalReasonCode}s, as the decline codes the first provider uses. */
  static final Map<String, String> DECLINE_CODES =
      Map.of("2", "card_declined", "12", "insufficient_funds", "6", "expired_card");

  /** Its {@code errorCode}s, as the error codes the first provider uses. */
  static final Map<String, String> ERROR_CODES =
      Map.of(
          "100", "invalid_request",
          "137", "invalid_amount",
          "180", "not_found",
          "167", "invalid_state",
          "168", "already_captured",
          "169", "authorization_expired",
          "704", "idempotency_error");

  private final HttpDependency http;

  HttpSecondaryPaymentGateway(HttpDependency http) {
    this.http = http;
  }

  @Override
  public CallResult<PaymentInfo> authorize(
      String idempotencyKey, String reference, Money amount, String paymentMethod) {
    return http.call(
        HttpMethod.POST,
        Map.of(
            "merchantReference",
            reference,
            "amount",
            Map.of("value", amount.minorUnits(), "currency", amount.currencyCode()),
            "paymentMethod",
            Map.of("type", "scheme", "token", paymentMethod)),
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u,
        (status, body) -> {
          if (status >= 300) {
            return rejected(body, "payment_error");
          }
          String result = body.path("resultCode").asString("");
          if ("Refused".equals(result)) {
            String code = body.path("refusalReasonCode").asString("");
            return new CallResult.Rejected<>(
                DECLINE_CODES.getOrDefault(code, "card_declined"),
                body.path("refusalReason").asString(""));
          }
          if (!"Authorised".equals(result)) {
            throw new IllegalStateException("secondary provider sent resultCode " + result);
          }
          return new CallResult.Success<>(payment(body));
        });
  }

  @Override
  public CallResult<PaymentInfo> capture(String idempotencyKey, String paymentId, long amount) {
    return http.call(
        HttpMethod.POST,
        Map.of("amount", Map.of("value", amount)),
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "captures"),
        this::paymentAnswer);
  }

  @Override
  public CallResult<PaymentInfo> reauthorize(String idempotencyKey, String paymentId) {
    return http.call(
        HttpMethod.POST,
        null,
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "extensions"),
        this::paymentAnswer);
  }

  @Override
  public CallResult<PaymentInfo> voidAuthorization(String idempotencyKey, String paymentId) {
    return http.call(
        HttpMethod.POST,
        null,
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "cancels"),
        this::paymentAnswer);
  }

  @Override
  public CallResult<RefundInfo> refund(
      String idempotencyKey, String paymentId, long amount, String reference) {
    return http.call(
        HttpMethod.POST,
        Map.of("amount", Map.of("value", amount), "merchantReference", reference),
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "refunds"),
        (status, body) ->
            status >= 300
                ? rejected(body, "refund_error")
                : new CallResult.Success<>(refund(body)));
  }

  @Override
  public CallResult<List<RefundInfo>> findRefunds(String paymentId) {
    return http.call(
        HttpMethod.GET,
        null,
        Map.of(),
        u -> u.pathSegment(paymentId, "refunds"),
        (status, body) -> {
          if (status >= 300) {
            return rejected(body, "lookup_failed");
          }
          List<RefundInfo> out = new ArrayList<>();
          for (JsonNode n : body.path("refunds")) {
            out.add(refund(n));
          }
          return new CallResult.Success<>(List.copyOf(out));
        });
  }

  @Override
  public CallResult<List<PaymentInfo>> findByReference(String reference) {
    return http.call(
        HttpMethod.GET,
        null,
        Map.of(),
        u -> u.queryParam("merchantReference", reference),
        (status, body) -> {
          if (status >= 300) {
            return rejected(body, "lookup_failed");
          }
          List<PaymentInfo> out = new ArrayList<>();
          for (JsonNode n : body.path("payments")) {
            out.add(payment(n));
          }
          return new CallResult.Success<>(List.copyOf(out));
        });
  }

  private CallResult<PaymentInfo> paymentAnswer(int status, JsonNode body) {
    if (status >= 300) {
      return rejected(body, "payment_error");
    }
    return new CallResult.Success<>(payment(body));
  }

  /** Its flat error body as a rejection with the first provider's code for the same error. */
  static <T> CallResult<T> rejected(JsonNode body, String fallbackCode) {
    String code = body.path("errorCode").asString("");
    String ours =
        code.isEmpty() ? fallbackCode : ERROR_CODES.getOrDefault(code, "secondary_" + code);
    return new CallResult.Rejected<>(ours, body.path("message").asString(""));
  }

  /**
   * One of its payment resources as a {@link PaymentInfo}.
   *
   * @throws IllegalStateException on a body that doesn't say what the payment is
   */
  static PaymentInfo payment(JsonNode n) {
    String id = text(n, "pspReference");
    String declineCode = null;
    if (n.hasNonNull("refusalReasonCode")) {
      declineCode =
          DECLINE_CODES.getOrDefault(n.path("refusalReasonCode").asString(""), "card_declined");
    }
    return new PaymentInfo(
        id,
        text(n, "merchantReference"),
        n.path("amount").path("value").asLong(),
        text(n.path("amount"), "currency"),
        status(n.path("status").asString("")),
        n.path("capturedAmount").path("value").asLong(0),
        n.path("refundedAmount").path("value").asLong(0),
        declineCode,
        instant(n, "expiresAt"));
  }

  static RefundInfo refund(JsonNode n) {
    return new RefundInfo(
        text(n, "pspReference"),
        text(n, "paymentPspReference"),
        n.path("merchantReference").asString(null),
        n.path("amount").path("value").asLong());
  }

  static PaymentStatus status(String s) {
    return switch (s) {
      case "authorised" -> PaymentStatus.AUTHORIZED;
      case "refused" -> PaymentStatus.DECLINED;
      case "captured" -> PaymentStatus.CAPTURED;
      case "cancelled" -> PaymentStatus.VOIDED;
      case "expired" -> PaymentStatus.EXPIRED;
      default -> throw new IllegalStateException("secondary provider sent status " + s);
    };
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (!v.isString() || v.asString().isEmpty()) {
      throw new IllegalStateException("secondary provider sent no " + field);
    }
    return v.asString();
  }

  private static Instant instant(JsonNode n, String field) {
    if (!n.hasNonNull(field)) {
      return null;
    }
    try {
      return Instant.parse(n.path(field).asString());
    } catch (DateTimeParseException e) {
      throw new IllegalStateException("secondary provider sent an unreadable " + field, e);
    }
  }
}
