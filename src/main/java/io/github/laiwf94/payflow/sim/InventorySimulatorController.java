package io.github.laiwf94.payflow.sim;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of {@link InventorySimulator}. */
@RestController
@EnabledSimulator
@RequestMapping("/sim/inventory/reservations")
class InventorySimulatorController {

  record ReserveRequest(String reservationId, List<InventorySimulator.Line> lines) {}

  private final InventorySimulator inventory;
  private final SimulatedResponses responses;

  InventorySimulatorController(InventorySimulator inventory, SimulatedResponses responses) {
    this.inventory = inventory;
    this.responses = responses;
  }

  @PostMapping
  ResponseEntity<?> reserve(@RequestBody ReserveRequest req) {
    return responses.respond(
        inventory.reserve(req.reservationId(), req.lines()),
        r -> ResponseEntity.status(201).body(r));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<?> release(@PathVariable String id) {
    return responses.respond(inventory.release(id), released -> ResponseEntity.noContent().build());
  }

  @GetMapping("/{id}")
  ResponseEntity<?> find(@PathVariable String id) {
    return responses.respond(
        inventory.find(id),
        r ->
            r.<ResponseEntity<?>>map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()));
  }
}
