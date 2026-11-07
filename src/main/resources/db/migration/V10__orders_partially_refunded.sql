-- PARTIALLY_REFUNDED: a completed order some but not all of whose capture has been refunded.
-- Like COMPLETED it is a resting state, never scheduled or leased; a later refund moves it on to
-- REFUNDED when the last of the money goes back. REFUNDING stays while the full-refund saga step
-- still uses it; it is removed once refunds run per refund row.
--
-- Each constraint is replaced NOT VALID, so the exclusive lock is held only for the catalog
-- change. Every new or updated row is checked from here on; V11 validates the existing rows
-- under a lock that lets reads and writes continue. The new rules only widen the old ones for
-- existing states, so validation can't fail on rows the old constraints accepted.

ALTER TABLE orders
    DROP CONSTRAINT orders_known_state,
    ADD CONSTRAINT orders_known_state CHECK (state IN (
        'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED', 'PAYMENT_CAPTURED', 'COMPLETED',
        'CANCELLING_SHIPMENT', 'REVERSING_PAYMENT', 'RELEASING_INVENTORY', 'FAILED', 'CANCELLED',
        'REFUNDING', 'PARTIALLY_REFUNDED', 'REFUNDED')) NOT VALID,

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
            ('COMPLETED', 'REFUNDING'),
            ('COMPLETED', 'PARTIALLY_REFUNDED'),
            ('COMPLETED', 'REFUNDED'),
            ('PARTIALLY_REFUNDED', 'REFUNDED'),
            ('REFUNDING', 'REFUNDED'))
        END) NOT VALID,

    DROP CONSTRAINT orders_completed_fully_captured,
    ADD CONSTRAINT orders_completed_fully_captured CHECK (
        state NOT IN ('COMPLETED', 'REFUNDING', 'PARTIALLY_REFUNDED')
        OR captured_minor = amount_minor) NOT VALID,

    ADD CONSTRAINT orders_partially_refunded_in_part CHECK (
        state <> 'PARTIALLY_REFUNDED'
        OR (refunded_minor > 0 AND refunded_minor < captured_minor)) NOT VALID,

    DROP CONSTRAINT orders_terminal_unscheduled,
    ADD CONSTRAINT orders_terminal_unscheduled CHECK (
        (next_attempt_at IS NULL) = (state IN (
            'COMPLETED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED'))) NOT VALID,

    DROP CONSTRAINT orders_terminal_unleased,
    ADD CONSTRAINT orders_terminal_unleased CHECK (
        lease_token IS NULL OR state NOT IN (
            'COMPLETED', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')) NOT VALID;
