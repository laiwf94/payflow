-- Why an order is being cancelled: the merchant asked (requested), the stock it held was kept
-- past the reservation window without being paid for (reservation_expired), or it was not paid
-- within the payment window after it was created (payment_expired). Set together with
-- cancel_requested and never cleared, so a CANCELLED order says why it was cancelled. Orders
-- cancelled before this column existed keep it NULL.
--
-- The column is new, so every existing row is NULL and passes the check. NOT VALID skips the
-- full-table scan under ADD COLUMN's ACCESS EXCLUSIVE lock; V25 validates it under a lock that
-- lets reads and writes continue. New rows are checked either way.

ALTER TABLE orders
    ADD COLUMN cancel_reason text,
    ADD CONSTRAINT orders_cancel_reason CHECK (
        cancel_reason IS NULL
        OR (cancel_requested
            AND cancel_reason IN ('requested', 'reservation_expired', 'payment_expired'))) NOT VALID;
