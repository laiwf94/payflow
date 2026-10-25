package io.github.laiwf94.payflow.order;

import java.util.UUID;

/**
 * An order leased to one worker. {@code recovered} is true when the previous lease expired instead
 * of being released, which means a worker died or stalled somewhere inside a step and that step's
 * external call may or may not have happened.
 */
public record Claim(Order order, UUID leaseToken, boolean recovered) {}
