package io.github.laiwf94.payflow.sim;

/** What happens to one simulated call. */
public enum Fault {
  /** Applied and answered. */
  OK,
  /** Refused with 503 before anything happened. */
  ERROR,
  /** Lost on the way in: nothing happened, but the caller never hears back. */
  UNKNOWN_BEFORE,
  /** Applied, then the answer is lost: the caller times out although the work was done. */
  UNKNOWN_AFTER
}
