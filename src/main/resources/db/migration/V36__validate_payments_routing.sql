-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V35's lock.

ALTER TABLE payments VALIDATE CONSTRAINT payments_routing_rule_format;
