package io.github.laiwf94.payflow.order;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Applies saga state changes. Every change the worker makes goes through here. */
@Component
public class OrderTransitions {

  private final OrderRepository repo;

  public OrderTransitions(OrderRepository repo) {
    this.repo = repo;
  }

  /**
   * Applies {@code change} to {@code before} if the caller still holds the lease. Returns the
   * updated order, or empty when the write was fenced out.
   */
  @Transactional
  public Optional<Order> apply(Order before, UUID leaseToken, StateChange change, Instant now) {
    return repo.update(before.id(), leaseToken, before.state(), change, now);
  }
}
