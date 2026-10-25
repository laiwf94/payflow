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

  /** Flags a cancel. Empty when the order doesn't exist for this merchant or is past capture. */
  @Transactional
  public Optional<Order> requestCancel(String merchantId, UUID id, Instant now) {
    return repo.requestCancel(merchantId, id, now);
  }

  /** Starts a full refund. Empty when the order doesn't exist for this merchant or isn't done. */
  @Transactional
  public Optional<Order> requestRefund(String merchantId, UUID id, Instant now) {
    return repo.requestRefund(merchantId, id, now);
  }
}
