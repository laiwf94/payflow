package io.github.laiwf94.payflow.timer;

import java.util.UUID;

/**
 * A timer leased to one worker. Every write the worker makes for it is fenced on {@code
 * leaseToken}.
 *
 * @param recovered the previous holder's lease ran out or was handed back mid-fire, rather than
 *     released: that worker died or was stopped while it held the timer
 */
public record TimerClaim(Timer timer, UUID leaseToken, boolean recovered) {}
