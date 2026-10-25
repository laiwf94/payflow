package io.github.laiwf94.payflow.sim;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Per-operation fault plans and the dice that apply them. Thread-safe. */
public final class Faults {

  private final Map<String, FaultPlan> plans = new HashMap<>();
  private final Map<String, ArrayDeque<Fault>> scripts = new HashMap<>();
  private RandomGenerator random;

  public Faults(RandomGenerator random) {
    this.random = random;
  }

  public synchronized void set(String operation, FaultPlan plan) {
    plans.put(operation, plan);
    scripts.put(operation, new ArrayDeque<>(plan.script()));
  }

  public synchronized FaultPlan get(String operation) {
    return plans.getOrDefault(operation, FaultPlan.NONE);
  }

  public synchronized void reset(RandomGenerator random) {
    plans.clear();
    scripts.clear();
    this.random = random;
  }

  /**
   * Runs {@code effect} under the operation's fault plan. A {@link SimulatorException} from the
   * effect propagates when the call is answered and is swallowed when the answer is lost, since the
   * caller can't see either way.
   */
  public <T> Simulated<T> run(String operation, Supplier<T> effect) {
    Fault fault;
    FaultPlan plan;
    synchronized (this) {
      plan = get(operation);
      ArrayDeque<Fault> script = scripts.get(operation);
      fault = script != null && !script.isEmpty() ? script.poll() : roll(plan);
    }
    return switch (fault) {
      case OK -> new Simulated.Answered<>(effect.get(), plan.latency());
      case ERROR -> new Simulated.Unavailable<>(plan.latency());
      case UNKNOWN_BEFORE -> new Simulated.Unanswered<>(null, plan.latency());
      case UNKNOWN_AFTER -> {
        T applied;
        try {
          applied = effect.get();
        } catch (SimulatorException e) {
          applied = null;
        }
        yield new Simulated.Unanswered<>(applied, plan.latency());
      }
    };
  }

  private Fault roll(FaultPlan plan) {
    double r = random.nextDouble();
    if (r < plan.errorRate()) {
      return Fault.ERROR;
    }
    r -= plan.errorRate();
    if (r < plan.unknownBeforeRate()) {
      return Fault.UNKNOWN_BEFORE;
    }
    r -= plan.unknownBeforeRate();
    if (r < plan.unknownAfterRate()) {
      return Fault.UNKNOWN_AFTER;
    }
    return Fault.OK;
  }
}
