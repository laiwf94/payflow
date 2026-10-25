package io.github.laiwf94.payflow.timeline;

/** A record that will never parse, so retrying it is pointless. */
final class MalformedEventException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  MalformedEventException(String message) {
    super(message);
  }
}
