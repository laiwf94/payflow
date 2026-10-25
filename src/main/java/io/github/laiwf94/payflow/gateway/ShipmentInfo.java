package io.github.laiwf94.payflow.gateway;

/** A shipment as the carrier reports it. */
public record ShipmentInfo(String id, String reference, String trackingNumber, String status) {

  public boolean cancelled() {
    return "CANCELLED".equals(status);
  }
}
