-- One row per tender of an order: what it pays, with which method, through which provider, the
-- state of that money, and the idempotency keys its provider calls are made with. Today every
-- order has exactly one tender (position 1) for its whole amount; split tender adds more.
--
-- Expand before contract: the saga still keeps its working copy of the single tender on the order
-- row (payment_id, payment_method, captured_minor, refunded_minor, authorization_expires_at,
-- reauthorized_at). Until the payment steps write these rows themselves, the trigger below keeps
-- position 1 equal to that copy in the same statement as every write of it, so the API can read
-- payments from here and never see the two disagree. V31 backfills the orders that existed before
-- this migration.
--
-- While the trigger writes these rows, a write the orders table accepts must never fail here, or
-- a legal saga step would be refused. So the checks below are ones the orders checks and the
-- state function already imply. Two rules wait for the steps to own the rows: a tender holding
-- money has a known provider id (a reversal can refund a capture whose answer was lost, and such
-- an order has none), and a provider id belongs to one tender (the simulator restarts its ids).
--
-- The new table takes no lock on orders beyond the catalog entries for the foreign key and the
-- triggers, which are brief, so this migration is safe while orders are being taken.

CREATE TABLE payments (
    id                       uuid        PRIMARY KEY,
    order_id                 uuid        NOT NULL REFERENCES orders (id),
    position                 smallint    NOT NULL,
    method                   text        NOT NULL,
    provider                 text        NOT NULL,
    amount_minor             bigint      NOT NULL,
    currency                 text        NOT NULL,
    state                    text        NOT NULL,
    provider_payment_id      text,
    authorize_key            text        NOT NULL,
    capture_key              text        NOT NULL,
    reauthorize_key          text        NOT NULL,
    captured_minor           bigint      NOT NULL DEFAULT 0,
    refunded_minor           bigint      NOT NULL DEFAULT 0,
    authorization_expires_at timestamptz,
    reauthorized_at          timestamptz,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,

    CONSTRAINT payments_order_position UNIQUE (order_id, position),
    CONSTRAINT payments_position_range CHECK (position BETWEEN 1 AND 16),
    CONSTRAINT payments_provider_format CHECK (provider ~ '^[a-z][a-z0-9_-]{0,31}$'),
    CONSTRAINT payments_amount_range CHECK (amount_minor BETWEEN 1 AND 99999999),
    CONSTRAINT payments_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT payments_known_state CHECK (state IN (
        'pending', 'authorized', 'captured', 'partially_refunded', 'refunded', 'voided',
        'expired', 'declined', 'canceled')),
    -- Each key is sent to the provider for this tender only, so no two tenders share one.
    CONSTRAINT payments_keys_distinct CHECK (
        authorize_key <> capture_key AND capture_key <> reauthorize_key
        AND authorize_key <> reauthorize_key),

    -- Money invariants per tender, as orders_money_bounds is for the order as a whole.
    CONSTRAINT payments_money_bounds CHECK (
        captured_minor BETWEEN 0 AND amount_minor AND refunded_minor BETWEEN 0 AND captured_minor),
    -- A tender holds money only once it is authorized, and the states say how much it holds.
    CONSTRAINT payments_state_money CHECK (CASE state
        WHEN 'pending' THEN captured_minor = 0
        WHEN 'authorized' THEN captured_minor = 0
        WHEN 'voided' THEN captured_minor = 0
        WHEN 'expired' THEN captured_minor = 0
        WHEN 'declined' THEN captured_minor = 0
        WHEN 'canceled' THEN captured_minor = 0
        WHEN 'captured' THEN captured_minor > 0 AND refunded_minor = 0
        WHEN 'partially_refunded' THEN refunded_minor > 0 AND refunded_minor < captured_minor
        WHEN 'refunded' THEN captured_minor > 0 AND refunded_minor = captured_minor
        END),
    CONSTRAINT payments_times_finite CHECK (
        (authorization_expires_at IS NULL OR isfinite(authorization_expires_at))
        AND (reauthorized_at IS NULL OR isfinite(reauthorized_at)))
);

-- The state of the single tender, read off the saga's working copy on the order row. Money moved
-- decides first; without any, an order past its payment steps tells why from its failure code
-- and whether the authorization was recorded. A failure that left a lost authorization behind
-- has no recorded id, so its void is reported as canceled.
CREATE FUNCTION payments_single_tender_state(
    order_state text, payment_id text, captured bigint, refunded bigint, failure_code text)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN refunded > 0 AND refunded = captured THEN 'refunded'
        WHEN refunded > 0 THEN 'partially_refunded'
        WHEN captured > 0 THEN 'captured'
        WHEN order_state IN ('RELEASING_INVENTORY', 'FAILED', 'CANCELLED') THEN CASE
            WHEN failure_code = 'payment_declined' THEN 'declined'
            WHEN failure_code = 'authorization_expired' THEN 'expired'
            WHEN payment_id IS NOT NULL THEN 'voided'
            ELSE 'canceled' END
        WHEN payment_id IS NOT NULL THEN 'authorized'
        ELSE 'pending'
        END
$$;

-- Writes position 1 from the order row: inserts it with the order, and on every later write of
-- the saga's copy brings it up to date. An upsert rather than an UPDATE, so an order V31 has not
-- reached yet gets its row from whichever comes first, and the other then finds it there.
CREATE FUNCTION payments_mirror_single_tender() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO payments (id, order_id, position, method, provider, amount_minor, currency,
        state, provider_payment_id, authorize_key, capture_key, reauthorize_key,
        captured_minor, refunded_minor, authorization_expires_at, reauthorized_at,
        created_at, updated_at)
    VALUES (gen_random_uuid(), NEW.id, 1, NEW.payment_method, 'primary', NEW.amount_minor,
        NEW.currency,
        payments_single_tender_state(NEW.state, NEW.payment_id, NEW.captured_minor,
            NEW.refunded_minor, NEW.failure_code),
        NEW.payment_id, 'authorize-' || NEW.id, 'capture-' || NEW.id, 'reauthorize-' || NEW.id,
        NEW.captured_minor, NEW.refunded_minor, NEW.authorization_expires_at,
        NEW.reauthorized_at, NEW.created_at, NEW.updated_at)
    ON CONFLICT (order_id, position) DO UPDATE SET
        state = EXCLUDED.state,
        provider_payment_id = EXCLUDED.provider_payment_id,
        captured_minor = EXCLUDED.captured_minor,
        refunded_minor = EXCLUDED.refunded_minor,
        authorization_expires_at = EXCLUDED.authorization_expires_at,
        reauthorized_at = EXCLUDED.reauthorized_at,
        updated_at = EXCLUDED.updated_at;
    RETURN NULL;
END
$$;

CREATE TRIGGER payments_mirror_insert
    AFTER INSERT ON orders
    FOR EACH ROW EXECUTE FUNCTION payments_mirror_single_tender();

-- Only writes that change the copy touch payments, so a lease claim or release costs nothing more.
CREATE TRIGGER payments_mirror_update
    AFTER UPDATE ON orders
    FOR EACH ROW
    WHEN ((OLD.state, OLD.payment_id, OLD.captured_minor, OLD.refunded_minor,
            OLD.authorization_expires_at, OLD.reauthorized_at, OLD.failure_code)
        IS DISTINCT FROM (NEW.state, NEW.payment_id, NEW.captured_minor, NEW.refunded_minor,
            NEW.authorization_expires_at, NEW.reauthorized_at, NEW.failure_code))
    EXECUTE FUNCTION payments_mirror_single_tender();
