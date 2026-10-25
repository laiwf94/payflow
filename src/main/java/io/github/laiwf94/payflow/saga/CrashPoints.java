package io.github.laiwf94.payflow.saga;

/**
 * Named places in the worker where a process could die. Production uses {@link #NONE}; the chaos
 * test throws from here to abandon a step half way, exactly as a killed process would.
 */
@FunctionalInterface
public interface CrashPoints {

  CrashPoints NONE = point -> {};

  void at(String point);
}
