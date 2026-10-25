package io.github.laiwf94.payflow.saga.steps;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts payment calls whose outcome was unknown and how each was later resolved. A rising
 * "applied" count means the provider is slow to answer but does the work; "not_applied" means
 * requests are getting lost on the way in.
 */
@Component
class UnknownOutcomes {

  private final MeterRegistry registry;

  UnknownOutcomes(MeterRegistry registry) {
    this.registry = registry;
  }

  void seen(String operation) {
    registry.counter("payflow.payment.unknown.outcomes", "operation", operation).increment();
  }

  void resolved(String operation, boolean applied) {
    registry
        .counter(
            "payflow.payment.unknown.resolved",
            "operation",
            operation,
            "resolution",
            applied ? "applied" : "not_applied")
        .increment();
  }
}
