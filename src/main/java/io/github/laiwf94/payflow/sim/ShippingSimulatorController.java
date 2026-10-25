package io.github.laiwf94.payflow.sim;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of {@link ShippingSimulator}. */
@RestController
@EnabledSimulator
@RequestMapping("/sim/shipping/shipments")
class ShippingSimulatorController {

  record CreateRequest(String reference, String postalCode, String country) {}

  private final ShippingSimulator shipping;
  private final SimulatedResponses responses;

  ShippingSimulatorController(ShippingSimulator shipping, SimulatedResponses responses) {
    this.shipping = shipping;
    this.responses = responses;
  }

  @PostMapping
  ResponseEntity<?> create(@RequestBody CreateRequest req) {
    return responses.respond(
        shipping.create(req.reference(), req.postalCode(), req.country()),
        s -> ResponseEntity.status(201).body(s));
  }

  @PostMapping("/{id}/cancel")
  ResponseEntity<?> cancel(@PathVariable String id) {
    return responses.respond(shipping.cancel(id), ResponseEntity::ok);
  }

  @GetMapping
  ResponseEntity<?> find(@RequestParam String reference) {
    return responses.respond(
        shipping.findByReference(reference),
        s -> ResponseEntity.ok(Map.of("data", s.stream().toList())));
  }
}
