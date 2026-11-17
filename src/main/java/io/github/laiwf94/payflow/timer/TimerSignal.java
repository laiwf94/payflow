package io.github.laiwf94.payflow.timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Lets a committed schedule wake the timer worker instead of waiting for its next poll. */
public class TimerSignal {

  private boolean woken;

  public synchronized void wakeUp() {
    woken = true;
    notifyAll();
  }

  /** Waits until woken or {@code max} passes, or {@code keepWaiting} turns false. */
  synchronized void await(Duration max, BooleanSupplier keepWaiting) throws InterruptedException {
    long deadline = System.nanoTime() + Math.max(1, max.toNanos());
    long left;
    while (!woken && keepWaiting.getAsBoolean() && (left = deadline - System.nanoTime()) > 0) {
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    woken = false;
  }
}
