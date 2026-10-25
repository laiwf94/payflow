package io.github.laiwf94.payflow.order;

import java.time.Instant;

/**
 * One fenced update of an order's saga state. Optional fields left {@code null} keep their stored
 * value; nothing in the saga ever needs to clear one.
 *
 * @param keepLeaseUntil when non-null the worker keeps the lease until then and carries on with the
 *     next step; when null the lease is released
 */
public record StateChange(
    OrderState state,
    int attempt,
    Instant stepStartedAt,
    Instant nextAttemptAt,
    Instant keepLeaseUntil,
    String reservationId,
    String paymentId,
    String shipmentId,
    String trackingNumber,
    Long capturedMinor,
    Long refundedMinor,
    String failureCode,
    String failureMessage) {

  public static Builder to(OrderState state) {
    return new Builder(state);
  }

  /** Builder so call sites name the fields they touch. */
  public static final class Builder {
    private final OrderState state;
    private int attempt;
    private Instant stepStartedAt;
    private Instant nextAttemptAt;
    private Instant keepLeaseUntil;
    private String reservationId;
    private String paymentId;
    private String shipmentId;
    private String trackingNumber;
    private Long capturedMinor;
    private Long refundedMinor;
    private String failureCode;
    private String failureMessage;

    private Builder(OrderState state) {
      this.state = state;
    }

    public Builder attempt(int attempt) {
      this.attempt = attempt;
      return this;
    }

    public Builder stepStartedAt(Instant at) {
      this.stepStartedAt = at;
      return this;
    }

    public Builder nextAttemptAt(Instant at) {
      this.nextAttemptAt = at;
      return this;
    }

    public Builder keepLeaseUntil(Instant until) {
      this.keepLeaseUntil = until;
      return this;
    }

    public Builder reservationId(String id) {
      this.reservationId = id;
      return this;
    }

    public Builder paymentId(String id) {
      this.paymentId = id;
      return this;
    }

    public Builder shipment(String id, String tracking) {
      this.shipmentId = id;
      this.trackingNumber = tracking;
      return this;
    }

    public Builder capturedMinor(long amount) {
      this.capturedMinor = amount;
      return this;
    }

    public Builder refundedMinor(long amount) {
      this.refundedMinor = amount;
      return this;
    }

    public Builder failure(String code, String message) {
      this.failureCode = code;
      this.failureMessage = message;
      return this;
    }

    public StateChange build() {
      return new StateChange(
          state,
          attempt,
          stepStartedAt,
          nextAttemptAt,
          keepLeaseUntil,
          reservationId,
          paymentId,
          shipmentId,
          trackingNumber,
          capturedMinor,
          refundedMinor,
          failureCode,
          failureMessage);
    }
  }
}
