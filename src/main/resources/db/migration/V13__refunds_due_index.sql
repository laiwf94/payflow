-- The refund worker's poll: pending refunds by when they are next due. Partial, so settled
-- refunds, which are nearly all of them, cost the index nothing.
--
-- CONCURRENTLY builds it without blocking writes; Flyway runs a migration that contains only such
-- statements outside a transaction. If the build fails it leaves an INVALID index behind: DROP
-- INDEX CONCURRENTLY refunds_due, then start the service again.

CREATE INDEX CONCURRENTLY refunds_due ON refunds (next_attempt_at)
    WHERE next_attempt_at IS NOT NULL;
