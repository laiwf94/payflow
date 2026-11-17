-- Durable timers: something that has to happen later even if every instance restarts in between,
-- such as releasing the stock an unpaid order holds. One row per pending timer, keyed by what it
-- is for (kind) and what it is about (subject_id, usually an order), so a subject has at most one
-- timer of each kind and scheduling it twice is harmless. A timer that fires is deleted in the
-- same transaction as its handler's writes, so it fires once or not at all.
--
-- next_attempt_at is when the timer worker should next look at it: due_at at first, pushed out by
-- a lease while a worker holds it (as the webhook dispatcher does), by backoff after its handler
-- failed, or set to -infinity when a stopping worker hands it back. lease_token is the fence: set
-- by a claim, checked by the fire and every other write the worker makes, and cleared by a
-- reschedule so that a fire already in flight for the old due time does nothing.
--
-- The table is new, so this takes no lock that orders or refunds wait on, and its index is built
-- on an empty table without CONCURRENTLY.

CREATE TABLE timers (
    kind            text        NOT NULL,
    subject_id      uuid        NOT NULL,
    due_at          timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    attempt         integer     NOT NULL DEFAULT 0,
    lease_token     uuid,
    last_error      text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,

    PRIMARY KEY (kind, subject_id),
    -- Handler names, the way the code spells them: timer.TimerHandlers checks the same pattern.
    CONSTRAINT timers_kind_format CHECK (kind ~ '^[a-z][a-z0-9_.]{0,62}$'),
    CONSTRAINT timers_attempt_nonnegative CHECK (attempt >= 0),
    -- Infinite times would never fire, or always; only a hand-back may use -infinity.
    CONSTRAINT timers_due_finite CHECK (isfinite(due_at)),
    CONSTRAINT timers_next_attempt CHECK (isfinite(next_attempt_at) OR next_attempt_at = '-infinity'),
    -- A fresh timer has no error; a retried one says why it was retried.
    CONSTRAINT timers_error_explained CHECK ((attempt = 0) = (last_error IS NULL)),
    CONSTRAINT timers_error_length CHECK (last_error IS NULL OR length(last_error) <= 512)
);

-- The worker's poll: timers by when they are next due.
CREATE INDEX timers_due ON timers (next_attempt_at);
