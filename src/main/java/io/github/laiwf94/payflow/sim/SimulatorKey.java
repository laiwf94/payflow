package io.github.laiwf94.payflow.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** The bearer key the simulators accept. Never logged; {@link #toString()} hides it. */
public final class SimulatorKey {

  private final String value;

  private SimulatorKey(String value) {
    this.value = value;
  }

  /** Uses {@code configured}, or a random key when it is blank. */
  public static SimulatorKey of(String configured) {
    if (configured != null && !configured.isBlank()) {
      if (configured.length() < 16) {
        throw new IllegalArgumentException("simulator key must be at least 16 characters");
      }
      return new SimulatorKey(configured);
    }
    byte[] b = new byte[24];
    new SecureRandom().nextBytes(b);
    return new SimulatorKey("sim_" + HexFormat.of().formatHex(b));
  }

  public String value() {
    return value;
  }

  /** Constant-time comparison against a presented key. */
  public boolean matches(String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        value.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String toString() {
    return "SimulatorKey[redacted]";
  }
}
