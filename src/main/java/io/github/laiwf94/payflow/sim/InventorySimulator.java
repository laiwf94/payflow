package io.github.laiwf94.payflow.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A stock ledger. A reservation holds stock for an order until it is released; reserving is
 * all-or-nothing across the lines and idempotent on the reservation id. A restock puts some of a
 * reservation's units back on the shelf, e.g. goods a customer returned; it too is all-or-nothing
 * and idempotent on an id the caller chooses, and can never return more of a SKU than the
 * reservation still holds. Releasing a reservation releases what it still holds and forgets its
 * restocks.
 *
 * <p>Unknown SKUs start with {@code defaultStock} units, so tests and the demo don't have to seed
 * every product; {@link #setStock} overrides that, e.g. to model a product that is sold out.
 */
public final class InventorySimulator {

  public record Line(String sku, int quantity) {}

  public record Reservation(String id, List<Line> lines) {}

  /** Units of a reservation put back into stock under {@code id}. */
  public record Restock(String id, String reservationId, List<Line> lines) {}

  private final Faults faults;
  private final long defaultStock;
  private final Map<String, Long> available = new HashMap<>();
  private final Map<String, Long> initial = new HashMap<>();
  private final Map<String, Reservation> reservations = new LinkedHashMap<>();

  /** Every restock by its id, and the same restocks by reservation, oldest first. */
  private final Map<String, Restock> restocks = new HashMap<>();

  private final Map<String, List<Restock>> restocksByReservation = new HashMap<>();

  public InventorySimulator(Faults faults, long defaultStock) {
    this.faults = faults;
    this.defaultStock = defaultStock;
  }

  public Simulated<Reservation> reserve(String reservationId, List<Line> lines) {
    validateId(reservationId, "reservation");
    validateLines(lines);
    List<Line> copy = List.copyOf(lines);
    return faults.run("inventory.reserve", () -> doReserve(reservationId, copy));
  }

  private static void validateId(String id, String what) {
    if (id == null || id.isBlank() || id.length() > 128) {
      throw new SimulatorException(400, "invalid_request", what + " id must be 1-128 chars");
    }
  }

  private static void validateLines(List<Line> lines) {
    if (lines == null
        || lines.isEmpty()
        || lines.stream().anyMatch(l -> l == null || l.sku() == null || l.quantity() <= 0)) {
      throw new SimulatorException(400, "invalid_request", "lines must have positive quantities");
    }
  }

  private synchronized Reservation doReserve(String id, List<Line> lines) {
    Reservation existing = reservations.get(id);
    if (existing != null) {
      if (!existing.lines().equals(lines)) {
        throw new SimulatorException(409, "conflict", "reservation exists with different lines");
      }
      return existing;
    }
    Map<String, Long> wanted = totals(lines);
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
    // Only what is still held goes back; restocked units already did. The restocks go with the
    // reservation, so a new reservation under the same id starts from nothing.
    Map<String, Long> returned = restocked(id);
    for (Map.Entry<String, Long> held : totals(r.lines()).entrySet()) {
      long units = held.getValue() - returned.getOrDefault(held.getKey(), 0L);
      available.put(held.getKey(), stock(held.getKey()) + units);
    }
    for (Restock x : restocksByReservation.getOrDefault(id, List.of())) {
      restocks.remove(x.id());
    }
    restocksByReservation.remove(id);
    return true;
  }

  /**
   * Puts {@code lines} of reservation {@code reservationId} back into stock, once per {@code
   * restockId}: a retry with the same id and lines replays the first restock, the same id with
   * anything else is a {@code conflict}. An unknown reservation is {@code reservation_not_found};
   * more of a SKU than the reservation still holds is {@code exceeds_reserved}, and nothing moves.
   */
  public Simulated<Restock> restock(String reservationId, String restockId, List<Line> lines) {
    validateId(reservationId, "reservation");
    validateId(restockId, "restock");
    validateLines(lines);
    List<Line> copy = List.copyOf(lines);
    return faults.run("inventory.restock", () -> doRestock(reservationId, restockId, copy));
  }

  private synchronized Restock doRestock(String reservationId, String id, List<Line> lines) {
    Restock existing = restocks.get(id);
    if (existing != null) {
      if (!existing.reservationId().equals(reservationId) || !existing.lines().equals(lines)) {
        throw new SimulatorException(409, "conflict", "restock exists with different lines");
      }
      return existing;
    }
    Reservation r = reservations.get(reservationId);
    if (r == null) {
      throw new SimulatorException(404, "reservation_not_found", "no reservation " + reservationId);
    }
    Map<String, Long> reserved = totals(r.lines());
    Map<String, Long> returned = restocked(reservationId);
    Map<String, Long> wanted = totals(lines);
    for (Map.Entry<String, Long> w : wanted.entrySet()) {
      long held = reserved.getOrDefault(w.getKey(), 0L) - returned.getOrDefault(w.getKey(), 0L);
      if (held < w.getValue()) {
        throw new SimulatorException(
            409,
            "exceeds_reserved",
            "reservation holds " + held + " of " + w.getKey() + ", not " + w.getValue());
      }
    }
    wanted.forEach((sku, qty) -> available.put(sku, stock(sku) + qty));
    Restock done = new Restock(id, reservationId, lines);
    restocks.put(id, done);
    restocksByReservation.computeIfAbsent(reservationId, k -> new ArrayList<>()).add(done);
    return done;
  }

  /** The restocks of a reservation, oldest first. */
  public synchronized List<Restock> restocksOf(String reservationId) {
    return List.copyOf(restocksByReservation.getOrDefault(reservationId, List.of()));
  }

  private Map<String, Long> restocked(String reservationId) {
    Map<String, Long> units = new HashMap<>();
    for (Restock x : restocksByReservation.getOrDefault(reservationId, List.of())) {
      for (Line l : x.lines()) {
        units.merge(l.sku(), (long) l.quantity(), Long::sum);
      }
    }
    return units;
  }

  private static Map<String, Long> totals(List<Line> lines) {
    Map<String, Long> units = new LinkedHashMap<>();
    for (Line l : lines) {
      units.merge(l.sku(), (long) l.quantity(), Long::sum);
    }
    return units;
  }

  /** {@link #restocksOf} under the lookup's fault plan. */
  public Simulated<List<Restock>> findRestocks(String reservationId) {
    return faults.run("inventory.lookup", () -> restocksOf(reservationId));
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

  /** Units of {@code sku} that reservations hold, net of what was restocked from them. */
  public synchronized long heldFor(String sku) {
    long held = 0;
    for (Reservation r : reservations.values()) {
      held += totals(r.lines()).getOrDefault(sku, 0L);
      held -= restocked(r.id()).getOrDefault(sku, 0L);
    }
    return held;
  }

  public synchronized Map<String, Reservation> reservations() {
    return Map.copyOf(reservations);
  }

  public synchronized void reset() {
    available.clear();
    initial.clear();
    reservations.clear();
    restocks.clear();
    restocksByReservation.clear();
  }
}
