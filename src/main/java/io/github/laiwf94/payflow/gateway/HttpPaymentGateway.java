package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** {@link PaymentGateway} over the provider's HTTP API. */
final class HttpPaymentGateway implements PaymentGateway {

  private final HttpDependency http;

  HttpPaymentGateway(HttpDependency http) {
    this.http = http;
  }

  @Override
  public CallResult<PaymentInfo> authorize(
      String idempotencyKey, String reference, Money amount, String paymentMethod) {
    return http.call(
        HttpMethod.POST,
        Map.of(
            "reference",
            reference,
            "amount",
            amount.minorUnits(),
            "currency",
            amount.currencyCode(),
            "payment_method",
            paymentMethod),
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment("authorizations"),
        (status, body) -> {
          if (status == 402) {
            JsonNode err = body.path("error");
            return new CallResult.Rejected<>(
                err.path("decline_code").asString("card_declined"),
                err.path("message").asString(""));
          }
          return payment(status, body);
        });
  }

  @Override
  public CallResult<PaymentInfo> capture(String idempotencyKey, String paymentId, long amount) {
    return http.call(
        HttpMethod.POST,
        Map.of("amount", amount),
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "capture"),
        this::payment);
  }

  @Override
  public CallResult<PaymentInfo> voidAuthorization(String idempotencyKey, String paymentId) {
    return http.call(
        HttpMethod.POST,
        null,
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "void"),
        this::payment);
  }

  @Override
  public CallResult<PaymentInfo> refund(String idempotencyKey, String paymentId, long amount) {
    return http.call(
        HttpMethod.POST,
        Map.of("amount", amount),
        Map.of("Idempotency-Key", idempotencyKey),
        u -> u.pathSegment(paymentId, "refunds"),
        this::payment);
  }

  @Override
  public CallResult<List<PaymentInfo>> findByReference(String reference) {
    return http.call(
        HttpMethod.GET,
        null,
        Map.of(),
        u -> u.queryParam("reference", reference),
        (status, body) -> {
          if (status >= 300) {
            return HttpDependency.rejected(body, "lookup_failed");
          }
          List<PaymentInfo> out = new ArrayList<>();
          for (JsonNode n : body.path("data")) {
            out.add(http.read(n, PaymentInfo.class));
          }
          return new CallResult.Success<>(List.copyOf(out));
        });
  }

  private CallResult<PaymentInfo> payment(int status, JsonNode body) {
    if (status >= 300) {
      return HttpDependency.rejected(body, "payment_error");
    }
    return new CallResult.Success<>(http.read(body, PaymentInfo.class));
  }
}
