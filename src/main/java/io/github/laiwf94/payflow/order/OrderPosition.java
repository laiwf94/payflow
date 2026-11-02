package io.github.laiwf94.payflow.order;

import java.time.Instant;
import java.util.UUID;

/**
 * A place in a merchant's listing, which runs newest first by {@code (created_at, id)}. The id
 * breaks ties between orders created in the same microsecond, so every order has exactly one
 * position and a page that ends on a tie resumes after it without skipping or repeating.
 */
public record OrderPosition(Instant createdAt, UUID id) {

  public static OrderPosition of(Order o) {
    return new OrderPosition(o.createdAt(), o.id());
  }
}
