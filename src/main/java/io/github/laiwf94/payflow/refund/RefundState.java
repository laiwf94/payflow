package io.github.laiwf94.payflow.refund;

/**
 * Where one refund is. A refund is created {@code PENDING}, already counting against its order's
 * capture, and settles exactly once: {@code SUCCEEDED} when the provider has returned the money,
 * {@code FAILED} when the provider refused it, which gives its share of the capture back. The same
 * rule is enforced by the {@code refunds_guard} trigger (see {@code V9__refunds.sql}).
 *
 * <pre>
 * PENDING -> SUCCEEDED
 *    |
 *    +----> FAILED
 * </pre>
 */
public enum RefundState {
  PENDING,
  SUCCEEDED,
  FAILED;

  public boolean canTransitionTo(RefundState next) {
    return this == PENDING && next != PENDING;
  }

  /** True once the refund has settled: it is never scheduled or leased again. */
  public boolean isTerminal() {
    return this != PENDING;
  }
}
