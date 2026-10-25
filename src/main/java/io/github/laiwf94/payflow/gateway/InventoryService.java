package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.order.LineItem;
import java.util.List;

/** Stock reservations, keyed by an id the caller chooses so retries are idempotent. */
public interface InventoryService {

  /** Holds stock for every line, or none. Not enough stock is {@link CallResult.Rejected}. */
  CallResult<String> reserve(String reservationId, List<LineItem> items);

  /** Releases the reservation. Releasing an unknown id succeeds. */
  CallResult<Boolean> release(String reservationId);
}
