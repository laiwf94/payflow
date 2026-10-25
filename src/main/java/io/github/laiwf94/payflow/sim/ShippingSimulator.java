package io.github.laiwf94.payflow.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A carrier that creates shipments (labels) and cancels them until they are picked up. One shipment
 * per reference: creating again with the same reference returns the existing one, which is how most
 * carrier APIs deduplicate.
 *
 * <p>Postal code {@code 00000} is undeliverable, a deterministic way to trigger a permanent
 * rejection in tests and the demo.
 */
public final class ShippingSimulator {

  public static final String UNDELIVERABLE_POSTAL_CODE = "00000";

  public enum Status {
    LABEL_CREATED,
    CANCELLED
  }

  public record Shipment(
      String id, String reference, String trackingNumber, String postalCode, Status status) {}

  private final Faults faults;
  private final Map<String, Shipment> byReference = new LinkedHashMap<>();
  private long nextId = 1;

  public ShippingSimulator(Faults faults) {
    this.faults = faults;
  }

  public Simulated<Shipment> create(String reference, String postalCode, String country) {
    if (reference == null || reference.isBlank() || reference.length() > 128) {
      throw new SimulatorException(400, "invalid_request", "reference must be 1-128 characters");
    }
    if (postalCode == null || country == null) {
      throw new SimulatorException(400, "invalid_request", "postal code and country are required");
    }
    return faults.run("shipping.create", () -> doCreate(reference, postalCode));
  }

  private synchronized Shipment doCreate(String reference, String postalCode) {
    Shipment existing = byReference.get(reference);
    if (existing != null) {
      return existing;
    }
    if (UNDELIVERABLE_POSTAL_CODE.equals(postalCode)) {
      throw new SimulatorException(422, "address_undeliverable", "carrier can't deliver there");
    }
    long n = nextId++;
    Shipment s =
        new Shipment(
            "shp_" + String.format("%08d", n),
            reference,
            "TRK" + String.format("%010d", n),
            postalCode,
            Status.LABEL_CREATED);
    byReference.put(reference, s);
    return s;
  }

  public Simulated<Shipment> cancel(String shipmentId) {
    return faults.run("shipping.cancel", () -> doCancel(shipmentId));
  }

  private synchronized Shipment doCancel(String shipmentId) {
    Shipment s =
        byReference.values().stream()
            .filter(x -> x.id().equals(shipmentId))
            .findFirst()
            .orElseThrow(() -> new SimulatorException(404, "not_found", "no such shipment"));
    Shipment c =
        new Shipment(s.id(), s.reference(), s.trackingNumber(), s.postalCode(), Status.CANCELLED);
    byReference.put(s.reference(), c);
    return c;
  }

  public Simulated<Optional<Shipment>> findByReference(String reference) {
    return faults.run("shipping.lookup", () -> shipment(reference));
  }

  public synchronized Optional<Shipment> shipment(String reference) {
    return Optional.ofNullable(byReference.get(reference));
  }

  public synchronized List<Shipment> shipments() {
    return List.copyOf(byReference.values());
  }

  public synchronized void reset() {
    byReference.clear();
    nextId = 1;
  }
}
