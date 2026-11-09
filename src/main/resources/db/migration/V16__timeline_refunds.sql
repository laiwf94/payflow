-- Refund events (refund.created, refund.succeeded, refund.failed) name the refund they are about.
-- The timeline keeps the refund's id and amount, and the provider's reason when it refused it, so
-- a merchant reading an order's history can tell its refunds apart.
--
-- The columns are new, so every existing row is NULL and passes the checks. NOT VALID skips the
-- full-table scan under the ACCESS EXCLUSIVE lock ADD COLUMN takes; V17 validates them under a
-- lock that lets the consumer keep writing. New rows are checked either way.

ALTER TABLE order_timeline
    ADD COLUMN refund_id uuid,
    ADD COLUMN refund_amount bigint,
    ADD COLUMN refund_failure_code text,
    ADD CONSTRAINT order_timeline_refund_pair
        CHECK ((refund_id IS NULL) = (refund_amount IS NULL)) NOT VALID,
    ADD CONSTRAINT order_timeline_refund_failure
        CHECK (refund_failure_code IS NULL OR refund_id IS NOT NULL) NOT VALID;
