package io.github.laiwf94.payflow.sim;

/** A business error a real provider would return with a 4xx status. */
public final class SimulatorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;

  public SimulatorException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
