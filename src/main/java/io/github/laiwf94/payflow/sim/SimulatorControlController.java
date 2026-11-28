package io.github.laiwf94.payflow.sim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Changes simulator behaviour at runtime and exposes the ledgers for audits and the demo. */
@RestController
@EnabledSimulator
@RequestMapping("/sim/control")
class SimulatorControlController {

  static final Set<String> OPERATIONS =
      Set.of(
          "payment.authorize",
          "payment.reauthorize",
          "payment.capture",
          "payment.void",
          "payment.refund",
          "payment.lookup",
          "secondary.authorize",
          "secondary.reauthorize",
          "secondary.capture",
          "secondary.void",
          "secondary.refund",
          "secondary.lookup",
          "inventory.reserve",
          "inventory.release",
          "inventory.restock",
          "inventory.lookup",
          "shipping.create",
          "shipping.cancel",
          "shipping.lookup");

  record FaultRequest(
      Double errorRate,
      Double unknownBeforeRate,
      Double unknownAfterRate,
      Long latencyMs,
      List<String> script,
      Double earlyExpiryRate) {}

  /** The operations that place a hold on a card, and so can have it expire early. */
  static final Set<String> HOLDS =
      Set.of(
          "payment.authorize",
          "payment.reauthorize",
          "secondary.authorize",
          "secondary.reauthorize");

  record SettingsRequest(
      Boolean honorIdempotencyKeys, Long answerDelayMs, Long authorizationLifetimeMs) {}

  record StockRequest(long available) {}

  private final Faults faults;
  private final SimulatorSettings settings;
  private final PaymentProviderSimulator payments;
  private final PaymentProviderSimulator secondary;
  private final InventorySimulator inventory;
  private final ShippingSimulator shipping;

  SimulatorControlController(
      Faults faults,
      SimulatorSettings settings,
      PaymentProviderSimulator payments,
      @Qualifier(SimulatorConfig.SECONDARY) PaymentProviderSimulator secondary,
      InventorySimulator inventory,
      ShippingSimulator shipping) {
    this.faults = faults;
    this.settings = settings;
    this.payments = payments;
    this.secondary = secondary;
    this.inventory = inventory;
    this.shipping = shipping;
  }

  @PutMapping("/faults/{operation}")
  ResponseEntity<?> setFaults(@PathVariable String operation, @RequestBody FaultRequest req) {
    if (!OPERATIONS.contains(operation)) {
      throw new SimulatorException(404, "not_found", "unknown operation " + operation);
    }
    List<Fault> script;
    FaultPlan plan;
    try {
      script =
          req.script() == null
              ? List.of()
              : req.script().stream().map(s -> Fault.valueOf(s.toUpperCase(Locale.ROOT))).toList();
      plan =
          new FaultPlan(
              orZero(req.errorRate()),
              orZero(req.unknownBeforeRate()),
              orZero(req.unknownAfterRate()),
              Duration.ofMillis(req.latencyMs() == null ? 0 : req.latencyMs()),
              script,
              orZero(req.earlyExpiryRate()));
      if (plan.earlyExpiryRate() > 0 && !HOLDS.contains(operation)) {
        throw new IllegalArgumentException(operation + " places no hold that could expire early");
      }
    } catch (IllegalArgumentException e) {
      throw new SimulatorException(400, "invalid_request", e.getMessage());
    }
    faults.set(operation, plan);
    return ResponseEntity.ok(plan);
  }

  @DeleteMapping("/faults")
  ResponseEntity<?> clearFaults() {
    faults.reset(new SplittableRandom());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/settings")
  ResponseEntity<?> settings(@RequestBody SettingsRequest req) {
    try {
      if (req.answerDelayMs() != null) {
        settings.answerDelay(Duration.ofMillis(req.answerDelayMs()));
      }
      if (req.authorizationLifetimeMs() != null) {
        settings.authorizationLifetime(Duration.ofMillis(req.authorizationLifetimeMs()));
      }
    } catch (IllegalArgumentException e) {
      throw new SimulatorException(400, "invalid_request", e.getMessage());
    }
    if (req.honorIdempotencyKeys() != null) {
      settings.honorIdempotencyKeys(req.honorIdempotencyKeys());
    }
    return ResponseEntity.ok(
        Map.of(
            "honor_idempotency_keys", settings.honorIdempotencyKeys(),
            "answer_delay_ms", settings.answerDelay().toMillis(),
            "authorization_lifetime_ms", settings.authorizationLifetime().toMillis()));
  }

  /**
   * The issuer drops a live authorization's hold now, at whichever provider holds {@code id}. 404
   * when there is none to drop.
   */
  @PostMapping("/payments/{id}/expire")
  ResponseEntity<?> expire(@PathVariable String id) {
    if (!payments.expireNow(id) && !secondary.expireNow(id)) {
      throw new SimulatorException(404, "not_found", "no live authorization " + id);
    }
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/stock/{sku}")
  ResponseEntity<?> stock(@PathVariable String sku, @RequestBody StockRequest req) {
    if (req.available() < 0 || req.available() > 1_000_000_000L) {
      throw new SimulatorException(400, "invalid_request", "available must be 0-1000000000");
    }
    inventory.setStock(sku, req.available());
    return ResponseEntity.ok(Map.of("sku", sku, "available", inventory.stock(sku)));
  }

  @GetMapping("/stock/{sku}")
  ResponseEntity<?> stock(@PathVariable String sku) {
    return ResponseEntity.ok(Map.of("sku", sku, "available", inventory.stock(sku)));
  }

  /**
   * Everything the simulated providers know about one order reference: its payments at the first
   * payment provider, every operation applied to them and their refund objects; the same at the
   * second provider ({@code secondary_*}); its reservation, the restocks from that reservation, and
   * its shipment. A tender's reference is the order id, or the order id and its position, so a
   * split order's later tenders are asked for by their own references.
   */
  @GetMapping("/ledger")
  ResponseEntity<?> ledger(@RequestParam String reference) {
    List<PaymentProviderSimulator.Payment> ps = payments.paymentsFor(reference);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payments", ps);
    body.put("payment_operations", payments.ledgerFor(reference));
    body.put("refunds", ps.stream().flatMap(p -> payments.refundsFor(p.id()).stream()).toList());
    List<PaymentProviderSimulator.Payment> ss = secondary.paymentsFor(reference);
    body.put("secondary_payments", ss);
    body.put("secondary_payment_operations", secondary.ledgerFor(reference));
    body.put(
        "secondary_refunds",
        ss.stream().flatMap(p -> secondary.refundsFor(p.id()).stream()).toList());
    body.put("reservation", inventory.reservation(reference).orElse(null));
    body.put("restocks", inventory.restocksOf(reference));
    body.put("shipment", shipping.shipment(reference).orElse(null));
    return ResponseEntity.ok(body);
  }

  @PostMapping("/reset")
  ResponseEntity<?> reset() {
    faults.reset(new SplittableRandom());
    payments.reset();
    secondary.reset();
    inventory.reset();
    shipping.reset();
    settings.honorIdempotencyKeys(true);
    settings.resetAuthorizationLifetime();
    return ResponseEntity.noContent().build();
  }

  private static double orZero(Double d) {
    return d == null ? 0 : d;
  }
}
