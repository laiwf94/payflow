package io.github.laiwf94.payflow.timer;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending timer: {@code kind} names the handler that fires it, {@code subjectId} what it is about
 * (usually an order). A subject has at most one timer of each kind.
 *
 * @param dueAt the earliest it may fire
 * @param attempt how many times its handler failed before
 * @param lastError why the last attempt failed; null before the first failure
 */
public record Timer(
    String kind,
    UUID subjectId,
    Instant dueAt,
    int attempt,
    String lastError,
    Instant createdAt,
    Instant updatedAt) {}
