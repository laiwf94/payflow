package io.github.laiwf94.payflow.refund;

import java.time.Duration;

/** What one attempt at a refund decided. The worker turns it into one fenced write. */
public sealed interface RefundOutcome {

  /** The provider returned the money; {@code providerRefundId} is its id for the refund. */
  record Succeeded(String providerRefundId) implements RefundOutcome {}

  /** The provider refused the refund. Nothing was returned and the share is given back. */
  record Failed(String code, String message) implements RefundOutcome {}

  /**
   * Not settled yet; try again later. {@code minDelay} is a floor on the backoff, used after an
   * unknown outcome to give the provider time to finish before asking what happened.
   */
  record Retry(String reason, Duration minDelay) implements RefundOutcome {
    public Retry(String reason) {
      this(reason, Duration.ZERO);
    }
  }
}
