package io.github.laiwf94.payflow.saga;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Saga worker settings ({@code payflow.saga.*}).
 *
 * @param enabled run the background worker; tests turn it off and drive {@link
 *     SagaWorker#runOnce()} by hand
 * @param lease how long a claimed order stays with one worker; must exceed the slowest step
 * @param pollInterval idle wait between polls when nothing woke the worker
 * @param batchSize orders claimed per poll
 * @param maxInFlight orders processed at once, each on its own virtual thread
 * @param backoffBase first retry ceiling
 * @param backoffCap largest retry ceiling
 * @param maxAttempts attempts a forward step gets before the order is compensated
 * @param stepTimeout time a forward step gets, retries included, before the order is compensated
 * @param unknownOutcomeDelay minimum wait before asking a dependency what an unanswered call did
 */
@ConfigurationProperties("payflow.saga")
public record SagaProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("30s") Duration lease,
    @DefaultValue("100ms") Duration pollInterval,
    @DefaultValue("64") int batchSize,
    @DefaultValue("256") int maxInFlight,
    @DefaultValue("200ms") Duration backoffBase,
    @DefaultValue("30s") Duration backoffCap,
    @DefaultValue("8") int maxAttempts,
    @DefaultValue("2m") Duration stepTimeout,
    @DefaultValue("2s") Duration unknownOutcomeDelay) {

  public SagaProperties {
    if (batchSize < 1 || batchSize > 1000 || maxInFlight < 1 || maxInFlight > 10_000) {
      throw new IllegalArgumentException("batch size must be 1-1000 and max in flight 1-10000");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("max attempts must be at least 1");
    }
    if (lease.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException("lease must be at least 1 s");
    }
  }
}
