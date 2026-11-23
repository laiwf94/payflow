package io.github.laiwf94.payflow.saga;

import io.github.laiwf94.payflow.order.OrderState;
import io.github.laiwf94.payflow.payment.Payment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What a saga step decided. The worker turns it into one fenced database write, which also records
 * the {@code tenders} the outcome carries: the order's payment rows as the step left them.
 */
public sealed interface StepOutcome {

  /** The payment rows the write records; empty for a step that changed none. */
  List<Payment> tenders();

  /**
   * The step's work is done; move to {@code next} and record {@code fields} and {@code tenders}.
   */
  record Advance(OrderState next, Fields fields, List<Payment> tenders) implements StepOutcome {
    public Advance {
      tenders = List.copyOf(tenders);
    }

    public Advance(OrderState next, Fields fields) {
      this(next, fields, List.of());
    }

    public Advance(OrderState next) {
      this(next, Fields.NONE);
    }
  }

  /**
   * Part of the step's work is done: record {@code fields} and {@code tenders}, keep the lease and
   * run the same step again straight away. A payment step that works through several tenders
   * returns this after each tender but the last, so a crash or retry starts from the next tender
   * rather than asking the provider about every one again. It does not count as an attempt and does
   * not restart the step's timeout.
   */
  record Progress(Fields fields, List<Payment> tenders) implements StepOutcome {
    public Progress {
      tenders = List.copyOf(tenders);
      if (tenders.isEmpty()) {
        throw new IllegalArgumentException("progress must record at least one tender");
      }
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

    @Override
    public List<Payment> tenders() {
      return List.of();
    }
  }

  /**
   * The step can't succeed (declined, out of stock). Undo from {@code compensateTo}, recording
   * {@code fields} and {@code tenders}: what the step learned before it failed, such as the tender
   * that was declined.
   */
  record Fail(
      String code, String message, OrderState compensateTo, Fields fields, List<Payment> tenders)
      implements StepOutcome {
    public Fail {
      tenders = List.copyOf(tenders);
    }

    public Fail(String code, String message, OrderState compensateTo) {
      this(code, message, compensateTo, Fields.NONE, List.of());
    }
  }

  /**
   * Nothing to do until someone else acts: leave the order where it is, unleased, until {@code
   * until}. A change made to the order meanwhile (a capture or cancel request) makes it due at once
   * instead, even one that lands while this step runs. Once {@code until} has passed with nothing
   * to do, the order fails as {@code onDeadline} says. Unlike a retry, waiting has no attempt count
   * and no step timeout: the wait is the step. {@code fields} are recorded as the wait begins: what
   * the step learned before deciding to wait again, such as a re-authorization's new expiry.
   */
  record Await(String reason, Instant until, Fail onDeadline, Fields fields, List<Payment> tenders)
      implements StepOutcome {
    public Await {
      if (until == null || onDeadline == null || fields == null) {
        throw new IllegalArgumentException("a wait needs a deadline and what happens at it");
      }
      tenders = List.copyOf(tenders);
    }

    public Await(String reason, Instant until, Fail onDeadline, Fields fields) {
      this(reason, until, onDeadline, fields, List.of());
    }

    public Await(String reason, Instant until, Fail onDeadline) {
      this(reason, until, onDeadline, Fields.NONE);
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
      Instant authorizationExpiresAt,
      Instant reauthorizedAt) {

    public static final Fields NONE = new Fields(null, null, null, null, null, null, null, null);

    public static Fields reservation(String id) {
      return new Fields(id, null, null, null, null, null, null, null);
    }

    public static Fields payment(String id) {
      return new Fields(null, id, null, null, null, null, null, null);
    }

    /** An authorized payment and when its hold on the card lapses. */
    public static Fields authorization(String id, Instant expiresAt) {
      return new Fields(null, id, null, null, null, null, expiresAt, null);
    }

    /** The authorization was extended at {@code at} and now lapses at {@code expiresAt}. */
    public static Fields reauthorization(Instant expiresAt, Instant at) {
      return new Fields(null, null, null, null, null, null, expiresAt, at);
    }

    public static Fields money(long captured, long refunded) {
      return new Fields(null, null, null, null, captured, refunded, null, null);
    }

    public static Fields shipment(String id, String tracking) {
      return new Fields(null, null, id, tracking, null, null, null, null);
    }
  }
}
