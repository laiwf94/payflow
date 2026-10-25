package io.github.laiwf94.payflow.outbox;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Lets a committed write wake the relay instead of waiting for its next poll. */
@Component
public class OutboxSignal {

  private boolean woken;

  public synchronized void wakeUp() {
    woken = true;
    notifyAll();
  }

  /** Waits until woken or {@code max} passes. */
  synchronized void await(Duration max) throws InterruptedException {
    long deadline = System.nanoTime() + Math.max(1, max.toNanos());
    long left;
    while (!woken && (left = deadline - System.nanoTime()) > 0) {
      TimeUnit.NANOSECONDS.timedWait(this, left);
    }
    woken = false;
  }
}
