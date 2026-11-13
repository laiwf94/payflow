package io.github.laiwf94.payflow.outbox;

import java.time.Instant;
import java.util.List;

/**
 * Told about every batch the relay publishes, inside the transaction that marks it published. What
 * it writes commits with the batch or not at all, so an event is handed on exactly as often as it
 * is marked published: a batch that fails to commit is published again and handed on again.
 */
@FunctionalInterface
public interface RelayListener {

  /** Listens to nothing. */
  RelayListener NONE = (ids, at) -> {};

  /**
   * Hands on a batch that was just published.
   *
   * @param outboxIds the outbox rows just published, still in the table
   * @param at when they were marked published
   */
  void relayed(List<Long> outboxIds, Instant at);
}
