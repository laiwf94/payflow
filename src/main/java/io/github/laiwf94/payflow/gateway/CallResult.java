package io.github.laiwf94.payflow.gateway;

/**
 * What a call to another system achieved, from the caller's point of view. The split between {@link
 * Transient} and {@link Unknown} is the important one: after a transient failure the call can
 * simply be retried; after an unknown one the caller has to find out what happened first.
 */
public sealed interface CallResult<T> {

  /** The call was applied and answered. */
  record Success<T>(T value) implements CallResult<T> {}

  /** A definite no from the other side (declined, out of stock, invalid state). */
  record Rejected<T>(String code, String message) implements CallResult<T> {}

  /** Definitely not applied: connection refused, 503, 429. Safe to retry as is. */
  record Transient<T>(String reason) implements CallResult<T> {}

  /** May or may not have been applied: timeout after sending, 500, 502, 504. */
  record Unknown<T>(String reason) implements CallResult<T> {}
}
