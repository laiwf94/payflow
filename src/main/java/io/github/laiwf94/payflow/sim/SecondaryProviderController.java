package io.github.laiwf94.payflow.sim;

import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Payment;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Refund;
import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The second payment provider's HTTP API, in the style of the result-code providers rather than the
 * first provider's: camelCase bodies, amounts as {@code {"value", "currency"}} objects, a {@code
 * pspReference} for every payment and refund, and modifications posted to sub-resources ({@code
 * captures}, {@code extensions}, {@code cancels}, {@code refunds}).
 *
 * <p>Its answers differ where it matters to an adapter:
 *
 * <ul>
 *   <li>A refused authorization is a 200 with {@code "resultCode": "Refused"}, a {@code
 *       refusalReason} and a numeric {@code refusalReasonCode}, not an HTTP error.
 *   <li>Errors are flat, {@code {"status", "errorCode", "errorType", "message"}}, with a numeric
 *       {@code errorCode} from {@link #ERROR_CODES}, and a refused request is a 422 rather than a
 *       400 or 409.
 *   <li>Statuses are lower case and spelled its own way: {@code authorised}, {@code refused},
 *       {@code captured}, {@code cancelled}, {@code expired}.
 * </ul>
 *
 * <p>Behind it is a {@link PaymentProviderSimulator} of its own, so the payments, keys, refunds,
 * ledger and {@code secondary.*} fault plans are separate from the first provider's.
 */
@RestController
@EnabledSimulator
@RequestMapping("/sim/secondary/payments")
class SecondaryProviderController {

  /** The provider's error codes, by the simulator's own error names. */
  static final Map<String, String> ERROR_CODES =
      Map.of(
          "invalid_request", "100",
          "invalid_amount", "137",
          "not_found", "180",
          "invalid_state", "167",
          "already_captured", "168",
          "authorization_expired", "169",
          "idempotency_error", "704",
          "unavailable", "905",
          "timeout", "906");

  /** Errors about the payment's state rather than the request's content. */
  static final java.util.Set<String> STATE_ERRORS =
      java.util.Set.of("invalid_state", "already_captured", "authorization_expired");

  /** Why an authorization was refused, as {@code refusalReasonCode} and {@code refusalReason}. */
  static final Map<String, List<String>> REFUSALS =
      Map.of(
          "card_declined", List.of("2", "Refused"),
          "insufficient_funds", List.of("12", "Not enough balance"),
          "expired_card", List.of("6", "Expired Card"));

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  record Amount(long value, String currency) {}

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  record ModificationAmount(long value) {}

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  record PaymentMethod(String type, String token) {}

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  record PaymentRequest(String merchantReference, Amount amount, PaymentMethod paymentMethod) {}

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  record CaptureRequest(ModificationAmount amount) {}

  @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
  record RefundRequest(ModificationAmount amount, String merchantReference) {}

  private final PaymentProviderSimulator provider;
  private final SimulatedResponses responses;

  SecondaryProviderController(
      @Qualifier(SimulatorConfig.SECONDARY) PaymentProviderSimulator provider,
      SimulatedResponses responses) {
    this.provider = provider;
    this.responses = responses;
  }

  @PostMapping
  ResponseEntity<?> authorize(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody PaymentRequest req) {
    if (req.amount() == null || req.amount().currency() == null || req.paymentMethod() == null) {
      throw new SimulatorException(400, "invalid_request", "amount and paymentMethod are required");
    }
    return responses.respond(
        provider.authorize(
            key,
            req.merchantReference(),
            req.amount().value(),
            req.amount().currency(),
            req.paymentMethod().token()),
        p -> {
          Map<String, Object> body = payment(p);
          body.put("resultCode", p.status() == Status.DECLINED ? "Refused" : "Authorised");
          return ResponseEntity.ok(body);
        },
        SecondaryProviderController::error);
  }

  @PostMapping("/{psp}/captures")
  ResponseEntity<?> capture(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String psp,
      @RequestBody CaptureRequest req) {
    if (req.amount() == null) {
      throw new SimulatorException(400, "invalid_request", "amount is required");
    }
    return responses.respond(
        provider.capture(key, psp, req.amount().value()),
        p -> ResponseEntity.ok(payment(p)),
        SecondaryProviderController::error);
  }

  @PostMapping("/{psp}/extensions")
  ResponseEntity<?> extend(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String psp) {
    return responses.respond(
        provider.reauthorize(key, psp),
        p -> ResponseEntity.ok(payment(p)),
        SecondaryProviderController::error);
  }

  @PostMapping("/{psp}/cancels")
  ResponseEntity<?> cancel(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String psp) {
    return responses.respond(
        provider.voidAuthorization(key, psp),
        p -> ResponseEntity.ok(payment(p)),
        SecondaryProviderController::error);
  }

  @PostMapping("/{psp}/refunds")
  ResponseEntity<?> refund(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String psp,
      @RequestBody RefundRequest req) {
    if (req.amount() == null) {
      throw new SimulatorException(400, "invalid_request", "amount is required");
    }
    return responses.respond(
        provider.refund(key, psp, req.amount().value(), req.merchantReference()),
        r -> ResponseEntity.status(201).body(refund(r)),
        SecondaryProviderController::error);
  }

  @GetMapping("/{psp}/refunds")
  ResponseEntity<?> refunds(@PathVariable String psp) {
    return responses.respond(
        provider.findRefunds(psp),
        list ->
            ResponseEntity.ok(
                Map.of("refunds", list.stream().map(SecondaryProviderController::refund).toList())),
        SecondaryProviderController::error);
  }

  @GetMapping
  ResponseEntity<?> find(@RequestParam String merchantReference) {
    return responses.respond(
        provider.findByReference(merchantReference),
        list ->
            ResponseEntity.ok(
                Map.of(
                    "payments", list.stream().map(SecondaryProviderController::payment).toList())),
        SecondaryProviderController::error);
  }

  // Handlers on the controller itself come before any advice, so this provider's routes answer in
  // its format rather than the first provider's (SimulatorErrors).
  @ExceptionHandler(SimulatorException.class)
  ResponseEntity<?> business(SimulatorException e) {
    int status = e.status() == 404 ? 404 : e.status() >= 500 ? e.status() : 422;
    return ResponseEntity.status(status).body(error(status, e.code(), e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<?> malformed(Exception e) {
    return ResponseEntity.badRequest().body(error(400, "invalid_request", "malformed request"));
  }

  static Map<String, Object> error(int status, String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status);
    body.put("errorCode", ERROR_CODES.getOrDefault(code, "000"));
    body.put(
        "errorType",
        status >= 500 ? "system" : STATE_ERRORS.contains(code) ? "business" : "validation");
    body.put("message", message);
    return body;
  }

  static Map<String, Object> payment(Payment p) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pspReference", p.id());
    body.put("merchantReference", p.reference());
    body.put("amount", Map.of("value", p.amount(), "currency", p.currency()));
    body.put("status", status(p.status()));
    body.put("capturedAmount", Map.of("value", p.captured(), "currency", p.currency()));
    body.put("refundedAmount", Map.of("value", p.refunded(), "currency", p.currency()));
    if (p.declineCode() != null) {
      List<String> why = REFUSALS.getOrDefault(p.declineCode(), List.of("0", "Unknown"));
      body.put("refusalReasonCode", why.get(0));
      body.put("refusalReason", why.get(1));
    }
    if (p.expiresAt() != null) {
      body.put("expiresAt", p.expiresAt().toString());
    }
    return body;
  }

  static Map<String, Object> refund(Refund r) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("pspReference", r.id());
    body.put("paymentPspReference", r.paymentId());
    body.put("merchantReference", r.reference());
    body.put("amount", Map.of("value", r.amount()));
    return body;
  }

  static String status(Status s) {
    return switch (s) {
      case AUTHORIZED -> "authorised";
      case DECLINED -> "refused";
      case CAPTURED -> "captured";
      case VOIDED -> "cancelled";
      case EXPIRED -> "expired";
    };
  }
}
