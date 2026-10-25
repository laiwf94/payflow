package io.github.laiwf94.payflow.timeline;

import java.time.Instant;

/** One event in an order's history, as the merchant sees it. */
public record TimelineEntry(
    long seq, String eventType, String state, String failureCode, Instant occurredAt) {}
