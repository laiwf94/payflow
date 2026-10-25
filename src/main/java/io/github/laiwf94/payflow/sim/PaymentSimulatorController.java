package io.github.laiwf94.payflow.sim;

import io.github.laiwf94.payflow.sim.PaymentProviderSimulator.Payment;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of {@link PaymentProviderSimulator}. */
@RestController
@EnabledSimulator
@RequestMapping("/sim/payments")
class PaymentSimulatorController {

  record AuthorizeRequest(String reference, long amount, String currency, String paymentMethod) {}

  record AmountRequest(long amount) {}

  private final PaymentProviderSimulator provider;
  private final SimulatedResponses responses;

  PaymentSimulatorController(PaymentProviderSimulator provider, SimulatedResponses responses) {
    this.provider = provider;
    this.responses = responses;
  }

  @PostMapping("/authorizations")
  ResponseEntity<?> authorize(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody AuthorizeRequest req) {
    return responses.respond(
        provider.authorize(key, req.reference(), req.amount(), req.currency(), req.paymentMethod()),
        p ->
            p.status() == PaymentProviderSimulator.Status.DECLINED
                ? ResponseEntity.status(402)
                    .body(
                        Map.of(
                            "error",
                            Map.of(
                                "code",
                                "card_declined",
                                "decline_code",
                                p.declineCode(),
                                "message",
                                "the card was declined",
                                "payment",
                                p)))
                : ResponseEntity.status(201).body(p));
  }

  @PostMapping("/{id}/capture")
  ResponseEntity<?> capture(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String id,
      @RequestBody AmountRequest req) {
    return responses.respond(provider.capture(key, id, req.amount()), ResponseEntity::ok);
  }

  @PostMapping("/{id}/void")
  ResponseEntity<?> voidAuthorization(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String id) {
    return responses.respond(provider.voidAuthorization(key, id), ResponseEntity::ok);
  }

  @PostMapping("/{id}/refunds")
  ResponseEntity<?> refund(
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String id,
      @RequestBody AmountRequest req) {
    return responses.respond(provider.refund(key, id, req.amount()), ResponseEntity::ok);
  }

  @GetMapping
  ResponseEntity<?> find(@RequestParam String reference) {
    return responses.respond(
        provider.findByReference(reference),
        list -> ResponseEntity.ok(Map.<String, Object>of("data", list.toArray(Payment[]::new))));
  }
}
