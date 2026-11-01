-- One order per (merchant, reference). NULLs are distinct, so orders without a reference never
-- collide. Order creation relies on this index for INSERT ... ON CONFLICT.
--
-- CONCURRENTLY builds the index without blocking writes; Flyway runs a migration that contains
-- only such statements outside a transaction. If the build fails it leaves an INVALID index
-- behind: DROP INDEX CONCURRENTLY orders_merchant_reference, then start the service again.

CREATE UNIQUE INDEX CONCURRENTLY orders_merchant_reference
    ON orders (merchant_id, merchant_reference);
