package io.github.laiwf94.payflow.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** A clock tests move by hand. Microsecond precision, like Postgres timestamps. */
public final class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start.truncatedTo(ChronoUnit.MICROS);
  }

  public void advance(Duration d) {
    now = now.plus(d);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
