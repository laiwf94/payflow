-- Delayed capture. capture_mode says who takes the money once the payment is authorized: payflow
-- at once (automatic, the behaviour before this column existed and still the default), or the
-- merchant with POST /v1/orders/{id}/capture, typically on shipment (manual). capture_requested
-- records that call; only a manual order can carry it. authorization_expires_at is when the hold
-- on the customer's card lapses, set when the order is authorized; a manual order still waiting
-- then fails into the reversal, which voids it and releases its stock.
--
-- A constant default is stored in the catalog, so ADD COLUMN rewrites nothing. The checks are
-- added NOT VALID to skip the full-table scan under ADD COLUMN's ACCESS EXCLUSIVE lock; V27
-- validates them under a lock that lets reads and writes continue. New rows are checked either
-- way, and every existing row has the defaults, which pass.

ALTER TABLE orders
    ADD COLUMN capture_mode text NOT NULL DEFAULT 'automatic',
    ADD COLUMN capture_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN authorization_expires_at timestamptz,
    ADD CONSTRAINT orders_capture_mode CHECK (capture_mode IN ('automatic', 'manual')) NOT VALID,
    ADD CONSTRAINT orders_capture_requested_manual CHECK (
        NOT capture_requested OR capture_mode = 'manual') NOT VALID,
    ADD CONSTRAINT orders_authorization_expiry_finite CHECK (
        authorization_expires_at IS NULL OR isfinite(authorization_expires_at)) NOT VALID;
