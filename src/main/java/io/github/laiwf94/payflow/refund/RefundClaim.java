package io.github.laiwf94.payflow.refund;

import java.util.UUID;

/**
 * A refund leased to one worker. {@code recovered} is true when the previous lease expired instead
 * of being released: a worker died or stalled while asking the provider, so the refund may or may
 * not have been made.
 */
public record RefundClaim(Refund refund, UUID leaseToken, boolean recovered) {}
