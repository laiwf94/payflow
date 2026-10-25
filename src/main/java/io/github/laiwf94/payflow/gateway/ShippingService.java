package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.order.ShippingAddress;
import java.util.Optional;

/** A carrier. One shipment per reference; creating again returns the existing shipment. */
public interface ShippingService {

  /** Creates the shipment. An address the carrier refuses is {@link CallResult.Rejected}. */
  CallResult<ShipmentInfo> create(String reference, ShippingAddress address);

  CallResult<ShipmentInfo> cancel(String shipmentId);

  CallResult<Optional<ShipmentInfo>> findByReference(String reference);
}
