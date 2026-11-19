package io.github.laiwf94.payflow.saga;

import io.github.laiwf94.payflow.order.OrderState;
import java.time.Duration;
import java.time.Instant;

/** What a saga step decided. The worker turns it into one fenced database write. */
public sealed interface StepOutcome {

  /** The step's work is done; move to {@code next} and record {@code fields}. */
  record Advance(OrderState next, Fields fields) implements StepOutcome {
    public Advance(OrderState next) {
      this(next, Fields.NONE);
    }
  }

  /**
   * Not done yet; try again later. {@code minDelay} is a floor on top of the backoff, used after an
   * unknown outcome to give the other side time to finish before we ask what happened.
   */
  record Retry(String reason, Duration minDelay) implements StepOutcome {
    public Retry(String reason) {
      this(reason, Duration.ZERO);
    }
  }

  /** The step can't succeed (declined, out of stock). Undo from {@code compensateTo}. */
  record Fail(String code, String message, OrderState compensateTo) implements StepOutcome {}

  /**
   * Nothing to do until someone else acts: leave the order where it is, unleased, until {@code
   * until}. A change made to the order meanwhile (a capture or cancel request) makes it due at once
   * instead, even one that lands while this step runs. Once {@code until} has passed with nothing
   * to do, the order fails as {@code onDeadline} says. Unlike a retry, waiting has no attempt count
   * and no step timeout: the wait is the step.
   */
  record Await(String reason, Instant until, Fail onDeadline) implements StepOutcome {
    public Await {
      if (until == null || onDeadline == null) {
        throw new IllegalArgumentException("a wait needs a deadline and what happens at it");
      }
    }
  }

  /** Values learned from a dependency. {@code null} leaves the stored value alone. */
  record Fields(
      String reservationId,
      String paymentId,
      String shipmentId,
      String trackingNumber,
      Long capturedMinor,
      Long refundedMinor,
      Instant authorizationExpiresAt) {

    public static final Fields NONE = new Fields(null, null, null, null, null, null, null);

    public static Fields reservation(String id) {
      return new Fields(id, null, null, null, null, null, null);
    }

    public static Fields payment(String id) {
      return new Fields(null, id, null, null, null, null, null);
    }

    /** An authorized payment and when its hold on the card lapses. */
    public static Fields authorization(String id, Instant expiresAt) {
      return new Fields(null, id, null, null, null, null, expiresAt);
    }

    public static Fields money(long captured, long refunded) {
      return new Fields(null, null, null, null, captured, refunded, null);
    }

    public static Fields shipment(String id, String tracking) {
      return new Fields(null, null, id, tracking, null, null, null);
    }
  }
}
