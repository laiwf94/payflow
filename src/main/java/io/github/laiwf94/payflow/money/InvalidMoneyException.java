package io.github.laiwf94.payflow.money;

/** An amount or currency that can't be charged. The message is safe to show to API clients. */
public final class InvalidMoneyException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  public InvalidMoneyException(String message) {
    super(message);
  }
}
