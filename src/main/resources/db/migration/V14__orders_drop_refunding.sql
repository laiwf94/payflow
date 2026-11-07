-- The contract half of moving refunds onto refund rows: no code writes REFUNDING any more (a full
-- refund is a refund row for whatever is left of the capture), so the state leaves the schema.
--
-- An order still in REFUNDING was being refunded by a 0.1.x instance's full-refund step, which
-- this release no longer has. Moving it here could refund it twice, so the migration refuses to
-- run while any exists: let the old instances finish those refunds (they take seconds), then start
-- this release again. The constraints are replaced first, under the exclusive lock the ALTER takes,
-- so no order can enter REFUNDING between the check and the commit.
--
-- previous_state keeps the state an order left last, so an order the old step refunded rests in
-- REFUNDED with previous_state REFUNDING; the transition list keeps that one pair for them.
--
-- As in V10 the constraints are replaced NOT VALID, so the exclusive lock lasts only for the
-- catalog change; V15 validates the existing rows without blocking reads or writes.

ALTER TABLE orders
    DROP CONSTRAINT orders_known_state,
    ADD CONSTRAINT orders_known_state CHECK (state IN (
        'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED', 'PAYMENT_CAPTURED', 'COMPLETED',
        'CANCELLING_SHIPMENT', 'REVERSING_PAYMENT', 'RELEASING_INVENTORY', 'FAILED', 'CANCELLED',
        'PARTIALLY_REFUNDED', 'REFUNDED')) NOT VALID,

    DROP CONSTRAINT orders_legal_transition,
    ADD CONSTRAINT orders_legal_transition CHECK (CASE
        WHEN previous_state IS NULL THEN state = 'PENDING'
        ELSE (previous_state, state) IN (
            ('PENDING', 'INVENTORY_RESERVED'),
            ('PENDING', 'RELEASING_INVENTORY'),
            ('INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED'),
            ('INVENTORY_RESERVED', 'REVERSING_PAYMENT'),
            ('INVENTORY_RESERVED', 'RELEASING_INVENTORY'),
            ('PAYMENT_AUTHORIZED', 'PAYMENT_CAPTURED'),
            ('PAYMENT_AUTHORIZED', 'REVERSING_PAYMENT'),
            ('PAYMENT_CAPTURED', 'COMPLETED'),
            ('PAYMENT_CAPTURED', 'CANCELLING_SHIPMENT'),
            ('CANCELLING_SHIPMENT', 'REVERSING_PAYMENT'),
            ('REVERSING_PAYMENT', 'RELEASING_INVENTORY'),
            ('RELEASING_INVENTORY', 'FAILED'),
            ('RELEASING_INVENTORY', 'CANCELLED'),
            ('COMPLETED', 'PARTIALLY_REFUNDED'),
            ('COMPLETED', 'REFUNDED'),
            ('PARTIALLY_REFUNDED', 'REFUNDED'),
            -- Orders the old step refunded keep previous_state REFUNDING for good. No order can
            -- enter REFUNDING any more, so this pair only ever describes those finished ones.
            ('REFUNDING', 'REFUNDED'))
        END) NOT VALID,

    DROP CONSTRAINT orders_completed_fully_captured,
    ADD CONSTRAINT orders_completed_fully_captured CHECK (
        state NOT IN ('COMPLETED', 'PARTIALLY_REFUNDED') OR captured_minor = amount_minor) NOT VALID;

DO $$
DECLARE
    left_over bigint;
BEGIN
    SELECT count(*) INTO left_over FROM orders WHERE state = 'REFUNDING';
    IF left_over > 0 THEN
        RAISE EXCEPTION 'orders_drop_refunding: % orders are still REFUNDING; let the running 0.1.x instances finish them, then start this release again',
                left_over
            USING ERRCODE = 'object_in_use';
    END IF;
END
$$;
