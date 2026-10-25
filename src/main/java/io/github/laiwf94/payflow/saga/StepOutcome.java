package io.github.laiwf94.payflow.saga;

import io.github.laiwf94.payflow.order.OrderState;
import java.time.Duration;

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

  /** Values learned from a dependency. {@code null} leaves the stored value alone. */
  record Fields(
      String reservationId,
      String paymentId,
      String shipmentId,
      String trackingNumber,
      Long capturedMinor,
      Long refundedMinor) {

    public static final Fields NONE = new Fields(null, null, null, null, null, null);

    public static Fields reservation(String id) {
      return new Fields(id, null, null, null, null, null);
    }

    public static Fields payment(String id) {
      return new Fields(null, id, null, null, null, null);
    }

    public static Fields money(long captured, long refunded) {
      return new Fields(null, null, null, null, captured, refunded);
    }

    public static Fields shipment(String id, String tracking) {
      return new Fields(null, null, id, tracking, null, null);
    }
  }
}
