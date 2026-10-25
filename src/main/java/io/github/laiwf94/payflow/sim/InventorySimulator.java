package io.github.laiwf94.payflow.sim;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A stock ledger. A reservation holds stock for an order until it is released; reserving is
 * all-or-nothing across the lines and idempotent on the reservation id.
 *
 * <p>Unknown SKUs start with {@code defaultStock} units, so tests and the demo don't have to seed
 * every product; {@link #setStock} overrides that, e.g. to model a product that is sold out.
 */
public final class InventorySimulator {

  public record Line(String sku, int quantity) {}

  public record Reservation(String id, List<Line> lines) {}

  private final Faults faults;
  private final long defaultStock;
  private final Map<String, Long> available = new HashMap<>();
  private final Map<String, Long> initial = new HashMap<>();
  private final Map<String, Reservation> reservations = new LinkedHashMap<>();

  public InventorySimulator(Faults faults, long defaultStock) {
    this.faults = faults;
    this.defaultStock = defaultStock;
  }

  public Simulated<Reservation> reserve(String reservationId, List<Line> lines) {
    if (reservationId == null || reservationId.isBlank() || reservationId.length() > 128) {
      throw new SimulatorException(400, "invalid_request", "reservation id must be 1-128 chars");
    }
    if (lines == null || lines.isEmpty() || lines.stream().anyMatch(l -> l.quantity() <= 0)) {
      throw new SimulatorException(400, "invalid_request", "lines must have positive quantities");
    }
    List<Line> copy = List.copyOf(lines);
    return faults.run("inventory.reserve", () -> doReserve(reservationId, copy));
  }

  private synchronized Reservation doReserve(String id, List<Line> lines) {
    Reservation existing = reservations.get(id);
    if (existing != null) {
      if (!existing.lines().equals(lines)) {
        throw new SimulatorException(409, "conflict", "reservation exists with different lines");
      }
      return existing;
    }
    Map<String, Long> wanted = new HashMap<>();
    for (Line l : lines) {
      wanted.merge(l.sku(), (long) l.quantity(), Long::sum);
    }
    for (Map.Entry<String, Long> w : wanted.entrySet()) {
      if (stock(w.getKey()) < w.getValue()) {
        throw new SimulatorException(409, "out_of_stock", "not enough stock for " + w.getKey());
      }
    }
    wanted.forEach((sku, qty) -> available.put(sku, stock(sku) - qty));
    Reservation r = new Reservation(id, lines);
    reservations.put(id, r);
    return r;
  }

  /** Releases a reservation. Releasing one that doesn't exist is a no-op, so retries are safe. */
  public Simulated<Boolean> release(String reservationId) {
    return faults.run("inventory.release", () -> doRelease(reservationId));
  }

  private synchronized Boolean doRelease(String id) {
    Reservation r = reservations.remove(id);
    if (r == null) {
      return false;
    }
    for (Line l : r.lines()) {
      available.put(l.sku(), stock(l.sku()) + l.quantity());
    }
    return true;
  }

  public Simulated<Optional<Reservation>> find(String reservationId) {
    return faults.run("inventory.lookup", () -> reservation(reservationId));
  }

  public synchronized Optional<Reservation> reservation(String id) {
    return Optional.ofNullable(reservations.get(id));
  }

  public synchronized void setStock(String sku, long units) {
    if (units < 0) {
      throw new IllegalArgumentException("stock can't be negative");
    }
    long held = heldFor(sku);
    available.put(sku, units);
    initial.put(sku, units + held);
  }

  public synchronized long stock(String sku) {
    initial.putIfAbsent(sku, defaultStock);
    return available.computeIfAbsent(sku, s -> defaultStock);
  }

  /** Stock the SKU started with (or was last set to), counting what is held. */
  public synchronized long initialStock(String sku) {
    stock(sku);
    return initial.get(sku);
  }

  public synchronized long heldFor(String sku) {
    return reservations.values().stream()
        .flatMap(r -> r.lines().stream())
        .filter(l -> l.sku().equals(sku))
        .mapToLong(Line::quantity)
        .sum();
  }

  public synchronized Map<String, Reservation> reservations() {
    return Map.copyOf(reservations);
  }

  public synchronized void reset() {
    available.clear();
    initial.clear();
    reservations.clear();
  }
}
