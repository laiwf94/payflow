-- The merchant's own reference for an order (their cart or order number), unique per merchant so
-- it can serve as a lookup key and a second guard against creating the same order twice.
--
-- The column is new, so every existing row is NULL and passes the check. NOT VALID skips the
-- full-table scan that would otherwise run here under the ACCESS EXCLUSIVE lock ADD COLUMN takes;
-- V6 validates it under a lock that lets reads and writes continue. New rows are checked either way.

ALTER TABLE orders
    ADD COLUMN merchant_reference text,
    ADD CONSTRAINT orders_merchant_reference_format
        CHECK (merchant_reference ~ '^[!-~]{1,128}$') NOT VALID;
