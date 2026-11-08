package io.github.laiwf94.payflow.gateway;

import io.github.laiwf94.payflow.order.LineItem;
import java.util.List;

/** Stock reservations, keyed by an id the caller chooses so retries are idempotent. */
public interface InventoryService {

  /** Holds stock for every line, or none. Not enough stock is {@link CallResult.Rejected}. */
  CallResult<String> reserve(String reservationId, List<LineItem> items);

  /** Releases the reservation. Releasing an unknown id succeeds. */
  CallResult<Boolean> release(String reservationId);

  /**
   * Puts {@code lines} of a reservation's units back into stock, e.g. goods a customer returned. It
   * is idempotent on {@code restockId}: after a lost answer the same call is a replay, not a second
   * restock. Returning more of a SKU than the reservation still holds, or restocking from an
   * unknown reservation, is {@link CallResult.Rejected}.
   */
  CallResult<String> restock(String reservationId, String restockId, List<StockLine> lines);
}
