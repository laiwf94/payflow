-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V16's exclusive lock.

ALTER TABLE order_timeline VALIDATE CONSTRAINT order_timeline_refund_pair;
ALTER TABLE order_timeline VALIDATE CONSTRAINT order_timeline_refund_failure;
