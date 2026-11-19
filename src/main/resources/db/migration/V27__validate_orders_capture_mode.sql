-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, which doesn't block reads or writes, so it
-- runs in its own migration rather than inside V26's exclusive lock.

ALTER TABLE orders VALIDATE CONSTRAINT orders_capture_mode;
ALTER TABLE orders VALIDATE CONSTRAINT orders_capture_requested_manual;
ALTER TABLE orders VALIDATE CONSTRAINT orders_authorization_expiry_finite;
