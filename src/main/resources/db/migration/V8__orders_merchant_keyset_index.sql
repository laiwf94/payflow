-- Keyset pagination over a merchant's orders walks (created_at, id): created_at alone has ties,
-- and the id breaks them so every order has exactly one position. The index scans in either
-- direction, so it replaces V1's (merchant_id, created_at DESC) index, which is dropped once the
-- new one exists. Both statements run outside a transaction; see V7 for recovering a failed build.

CREATE INDEX CONCURRENTLY orders_merchant_created_id ON orders (merchant_id, created_at, id);

DROP INDEX CONCURRENTLY orders_merchant_created;
