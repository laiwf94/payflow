-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V33's exclusive lock.

ALTER TABLE refunds
    VALIDATE CONSTRAINT refunds_known_state,
    VALIDATE CONSTRAINT refunds_returned,
    VALIDATE CONSTRAINT refunds_partially_failed_identified,
    VALIDATE CONSTRAINT refunds_partially_failed_explained,
    VALIDATE CONSTRAINT refunds_allocation_policy;
