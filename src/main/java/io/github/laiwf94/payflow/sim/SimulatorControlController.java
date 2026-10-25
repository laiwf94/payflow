package io.github.laiwf94.payflow.sim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
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
          "payment.capture",
          "payment.void",
          "payment.refund",
          "payment.lookup",
          "inventory.reserve",
          "inventory.release",
          "inventory.lookup",
          "shipping.create",
          "shipping.cancel",
          "shipping.lookup");

  record FaultRequest(
      Double errorRate,
      Double unknownBeforeRate,
      Double unknownAfterRate,
      Long latencyMs,
      List<String> script) {}

  record SettingsRequest(Boolean honorIdempotencyKeys, Long answerDelayMs) {}

  record StockRequest(long available) {}

  private final Faults faults;
  private final SimulatorSettings settings;
  private final PaymentProviderSimulator payments;
  private final InventorySimulator inventory;
  private final ShippingSimulator shipping;

  SimulatorControlController(
      Faults faults,
      SimulatorSettings settings,
      PaymentProviderSimulator payments,
      InventorySimulator inventory,
      ShippingSimulator shipping) {
    this.faults = faults;
    this.settings = settings;
    this.payments = payments;
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
              script);
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
    } catch (IllegalArgumentException e) {
      throw new SimulatorException(400, "invalid_request", e.getMessage());
    }
    if (req.honorIdempotencyKeys() != null) {
      settings.honorIdempotencyKeys(req.honorIdempotencyKeys());
    }
    return ResponseEntity.ok(
        Map.of(
            "honor_idempotency_keys", settings.honorIdempotencyKeys(),
            "answer_delay_ms", settings.answerDelay().toMillis()));
  }

  @PutMapping("/stock/{sku}")
  ResponseEntity<?> stock(@PathVariable String sku, @RequestBody StockRequest req) {
    if (req.available() < 0 || req.available() > 1_000_000_000L) {
      throw new SimulatorException(400, "invalid_request", "available must be 0-1000000000");
    }
    inventory.setStock(sku, req.available());
    return ResponseEntity.ok(Map.of("sku", sku, "available", inventory.stock(sku)));
  }

  /** Everything the three providers know about one order reference. */
  @GetMapping("/ledger")
  ResponseEntity<?> ledger(@RequestParam String reference) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payments", payments.paymentsFor(reference));
    body.put("payment_operations", payments.ledgerFor(reference));
    body.put("reservation", inventory.reservation(reference).orElse(null));
    body.put("shipment", shipping.shipment(reference).orElse(null));
    return ResponseEntity.ok(body);
  }

  @PostMapping("/reset")
  ResponseEntity<?> reset() {
    faults.reset(new SplittableRandom());
    payments.reset();
    inventory.reset();
    shipping.reset();
    settings.honorIdempotencyKeys(true);
    return ResponseEntity.noContent().build();
  }

  private static double orZero(Double d) {
    return d == null ? 0 : d;
  }
}
