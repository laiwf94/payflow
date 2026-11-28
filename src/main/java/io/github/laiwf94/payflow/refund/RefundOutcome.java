package io.github.laiwf94.payflow.refund;

import java.time.Duration;
import java.util.UUID;

/** What one attempt at a refund decided. The worker turns it into one fenced write. */
public sealed interface RefundOutcome {

  /** The provider returned all of the money; {@code providerRefundId} is its id for the refund. */
  record Succeeded(String providerRefundId) implements RefundOutcome {}

  /** The provider refused the refund. Nothing was returned and the share is given back. */
  record Failed(String code, String message) implements RefundOutcome {}

  /**
   * Some tenders returned their share and others refused. {@code returnedMinor} went back; the rest
   * is given back to be refunded again. {@code providerRefundId} is the first share's id.
   */
  record PartiallyFailed(String providerRefundId, long returnedMinor, String code, String message)
      implements RefundOutcome {}

  /**
   * One tender's share settled; the refund is not done yet. The worker records the share and runs
   * the attempt again under the same lease for the next one.
   *
   * @param providerRefundId the provider's id for the share's refund, when it succeeded
   * @param code why the provider refused the share, when it did
   */
  record ShareSettled(UUID paymentId, String providerRefundId, String code, String message)
      implements RefundOutcome {

    public static ShareSettled succeeded(UUID paymentId, String providerRefundId) {
      return new ShareSettled(paymentId, providerRefundId, null, null);
    }

    public static ShareSettled refused(UUID paymentId, String code, String message) {
      return new ShareSettled(paymentId, null, code, message);
    }

    public boolean succeeded() {
      return providerRefundId != null;
    }
  }

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
