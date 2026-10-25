package io.github.laiwf94.payflow.order;

import io.github.laiwf94.payflow.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sample orders for tests. */
public final class Orders {

  public static final ShippingAddress ADDRESS =
      new ShippingAddress("Alice Example", "1 Main Street", null, "Springfield", "12345", "NL");

  private Orders() {}

  public static Order pending(String merchant, Instant now) {
    return Order.create(
        UUID.randomUUID(),
        merchant,
        Money.of(2500, "EUR"),
        List.of(new LineItem("sku-1", 2, 1000), new LineItem("sku-2", 1, 500)),
        ADDRESS,
        "pm_card_ok",
        "corr-" + UUID.randomUUID(),
        now);
  }
}
