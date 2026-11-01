-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V5's exclusive lock.

ALTER TABLE orders VALIDATE CONSTRAINT orders_merchant_reference_format;
