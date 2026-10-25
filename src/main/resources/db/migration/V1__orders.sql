-- One row per order. The row is also the saga's durable state: which step is next, when to try
-- it, and which worker holds the lease on it.

CREATE TABLE orders (
    id                uuid        PRIMARY KEY,
    merchant_id       text        NOT NULL,
    state             text        NOT NULL,
    previous_state    text,
    amount_minor      bigint      NOT NULL,
    currency          text        NOT NULL,
    items             jsonb       NOT NULL,
    shipping_address  jsonb       NOT NULL,
    payment_method    text        NOT NULL,
    cancel_requested  boolean     NOT NULL DEFAULT false,
    reservation_id    text,
    payment_id        text,
    shipment_id       text,
    tracking_number   text,
    captured_minor    bigint      NOT NULL DEFAULT 0,
    refunded_minor    bigint      NOT NULL DEFAULT 0,
    failure_code      text,
    failure_message   text,
    attempt           integer     NOT NULL DEFAULT 0,
    step_started_at   timestamptz NOT NULL,
    next_attempt_at   timestamptz,
    lease_token       uuid,
    lease_until       timestamptz,
    correlation_id    text        NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,

    CONSTRAINT orders_merchant_format CHECK (merchant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT orders_amount_range CHECK (amount_minor BETWEEN 1 AND 99999999),
    CONSTRAINT orders_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT orders_attempt_nonnegative CHECK (attempt >= 0),
    CONSTRAINT orders_known_state CHECK (state IN (
        'PENDING', 'INVENTORY_RESERVED', 'PAYMENT_AUTHORIZED', 'PAYMENT_CAPTURED', 'COMPLETED',
        'CANCELLING_SHIPMENT', 'REVERSING_PAYMENT', 'RELEASING_INVENTORY', 'FAILED', 'CANCELLED',
        'REFUNDING', 'REFUNDED')),

    -- A CHECK constraint can't see the old row, so the trigger below copies the old state into
    -- previous_state on every state change and this constraint judges the pair. The CASE matters:
    -- a bare "(NULL, x) IN (...)" is NULL, and a NULL CHECK passes.
    CONSTRAINT orders_legal_transition CHECK (CASE
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
            ('REFUNDING', 'REFUNDED'))
        END),

    -- Money invariants. Whatever the code does, the database refuses a completed order that was
    -- not fully captured or a failed order that kept any of the customer's money.
    CONSTRAINT orders_money_bounds CHECK (
        captured_minor BETWEEN 0 AND amount_minor AND refunded_minor BETWEEN 0 AND captured_minor),
    CONSTRAINT orders_completed_fully_captured CHECK (
        state NOT IN ('COMPLETED', 'REFUNDING') OR captured_minor = amount_minor),
    CONSTRAINT orders_completed_not_refunded CHECK (state <> 'COMPLETED' OR refunded_minor = 0),
    CONSTRAINT orders_refunded_in_full CHECK (
        state <> 'REFUNDED' OR (captured_minor = amount_minor AND refunded_minor = captured_minor)),
    CONSTRAINT orders_failed_net_zero CHECK (
        state NOT IN ('FAILED', 'CANCELLED') OR refunded_minor = captured_minor),

    -- Terminal orders are never scheduled and never leased.
    CONSTRAINT orders_terminal_unscheduled CHECK (
        (next_attempt_at IS NULL) = (state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED'))),
    CONSTRAINT orders_lease_pair CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT orders_terminal_unleased CHECK (
        lease_token IS NULL OR state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED'))
);

CREATE FUNCTION orders_record_previous_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.previous_state := NULL;
    ELSIF NEW.state IS DISTINCT FROM OLD.state THEN
        NEW.previous_state := OLD.state;
    ELSE
        NEW.previous_state := OLD.previous_state;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER orders_previous_state
    BEFORE INSERT OR UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_record_previous_state();

CREATE INDEX orders_due ON orders (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE INDEX orders_merchant_created ON orders (merchant_id, created_at DESC);
