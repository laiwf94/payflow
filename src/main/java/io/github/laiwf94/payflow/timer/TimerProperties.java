package io.github.laiwf94.payflow.timer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timer worker settings ({@code payflow.timers.*}).
 *
 * @param enabled run the background worker; tests turn it off and drive {@link
 *     TimerWorker#runOnce()} by hand
 * @param pollInterval idle wait between polls when no schedule woke the worker; a timer fires at
 *     most this long after it is due, when the worker is idle
 * @param batchSize timers claimed per poll
 * @param concurrency timers fired at once, each in its own transaction; keep it well below the
 *     connection pool
 * @param lease how long a claimed timer stays with one worker; a worker that dies holding it delays
 *     it by at most this much
 * @param retryBase the first retry's backoff ceiling after a handler failed; it doubles per attempt
 *     (full jitter)
 * @param retryCap the most the backoff ceiling grows to
 * @param shutdownGrace how long a stopping worker waits for fires in flight before handing them
 *     back
 */
@ConfigurationProperties("payflow.timers")
public record TimerProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("100ms") Duration pollInterval,
    @DefaultValue("256") int batchSize,
    @DefaultValue("8") int concurrency,
    @DefaultValue("30s") Duration lease,
    @DefaultValue("200ms") Duration retryBase,
    @DefaultValue("30s") Duration retryCap,
    @DefaultValue("5s") Duration shutdownGrace) {

  public TimerProperties {
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("timer poll interval must be positive");
    }
    if (batchSize < 1 || batchSize > 1000 || concurrency < 1 || concurrency > 256) {
      throw new IllegalArgumentException("timer batch size must be 1-1000 and concurrency 1-256");
    }
    if (lease.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException("timer lease must be at least 1 s");
    }
    if (retryBase.isNegative() || retryBase.isZero() || retryCap.compareTo(retryBase) < 0) {
      throw new IllegalArgumentException("timer retries need 0 < base <= cap");
    }
    if (shutdownGrace.isNegative() || shutdownGrace.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("timer shutdown grace must be 0-5 min");
    }
  }
}
