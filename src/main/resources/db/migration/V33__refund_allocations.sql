-- Refunds across tenders. A refund of an order paid several ways is split between its tenders by
-- an allocation policy (see refund/AllocationPolicy), and each tender's share is a row here: made
-- at that tender's provider payment, under its own key, and settled on its own. The refund row
-- stays what the merchant asked for and settles once every share has: SUCCEEDED when all of them
-- returned their money, FAILED when none did, and the new PARTIALLY_FAILED when some did, with
-- returned_minor saying how much.
--
-- Expand before contract, as with payments. During a rolling upgrade an older binary still
-- creates refunds without shares and settles refunds itself, always against the order's first
-- tender, which is the only tender any order it can see has:
--   * a refund without shares is given them by this binary's worker when it first claims it;
--   * an older binary settling a refund this binary split sets the refund's state alone, so the
--     trigger below settles its single pending share the same way. This binary's own writes set
--     payflow.refund_writer to 'allocations' in the statement and are left alone.
-- Existing refunds get their one share here, on the order's first tender.
--
-- Each new constraint on refunds is added NOT VALID, so the ACCESS EXCLUSIVE lock is held only for
-- the catalog change; V34 validates them. The new columns are NULL on every existing row, which
-- the checks accept. The new table takes no lock on refunds or payments beyond the catalog entries
-- for its foreign keys.

ALTER TABLE refunds
    ADD COLUMN returned_minor bigint,
    ADD COLUMN allocation_policy text,
    DROP CONSTRAINT refunds_known_state,
    ADD CONSTRAINT refunds_known_state
        CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED', 'PARTIALLY_FAILED')) NOT VALID,
    -- What went back. NULL on refunds an older binary settled, where the state says it: all of it
    -- for SUCCEEDED, none for FAILED. A partial failure always records it.
    ADD CONSTRAINT refunds_returned CHECK (CASE state
        WHEN 'PENDING' THEN returned_minor IS NULL
        WHEN 'SUCCEEDED' THEN returned_minor IS NULL OR returned_minor = amount_minor
        WHEN 'FAILED' THEN returned_minor IS NULL OR returned_minor = 0
        WHEN 'PARTIALLY_FAILED' THEN returned_minor IS NOT NULL
            AND returned_minor BETWEEN 1 AND amount_minor - 1
        END) NOT VALID,
    ADD CONSTRAINT refunds_partially_failed_identified
        CHECK (state <> 'PARTIALLY_FAILED' OR provider_refund_id IS NOT NULL) NOT VALID,
    ADD CONSTRAINT refunds_partially_failed_explained
        CHECK (state <> 'PARTIALLY_FAILED' OR failure_code IS NOT NULL) NOT VALID,
    ADD CONSTRAINT refunds_allocation_policy
        CHECK (allocation_policy IN ('reverse_order', 'proportional', 'card_first')) NOT VALID;

CREATE TABLE refund_allocations (
    refund_id          uuid        NOT NULL REFERENCES refunds (id),
    payment_id         uuid        NOT NULL REFERENCES payments (id),
    -- The order the shares are made in, from 1: the policy's order.
    seq                smallint    NOT NULL,
    amount_minor       bigint      NOT NULL,
    state              text        NOT NULL,
    provider_refund_id text,
    failure_code       text,
    failure_message    text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,

    -- At most one share of a refund per tender, so the refund's id is a unique reference for it
    -- at that tender's provider payment.
    PRIMARY KEY (refund_id, payment_id),
    CONSTRAINT refund_allocations_seq UNIQUE (refund_id, seq),
    CONSTRAINT refund_allocations_seq_range CHECK (seq BETWEEN 1 AND 16),
    CONSTRAINT refund_allocations_amount_range CHECK (amount_minor BETWEEN 1 AND 99999999),
    CONSTRAINT refund_allocations_known_state CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT refund_allocations_succeeded_identified
        CHECK (state <> 'SUCCEEDED' OR provider_refund_id IS NOT NULL),
    CONSTRAINT refund_allocations_failed_explained
        CHECK (state <> 'FAILED' OR failure_code IS NOT NULL)
);

-- A tender's shares, for the sum the check below takes and the service sizes new refunds with.
CREATE INDEX refund_allocations_payment ON refund_allocations (payment_id);

-- A share's refund, tender, amount and order never change, and its state moves only out of
-- PENDING. The provider's id, once known, is kept.
CREATE FUNCTION refund_allocations_guard_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.refund_id <> OLD.refund_id OR NEW.payment_id <> OLD.payment_id
            OR NEW.amount_minor <> OLD.amount_minor OR NEW.seq <> OLD.seq
            OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'refund_allocations_immutable: a share''s refund, tender and amount never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_immutable';
    END IF;
    IF OLD.provider_refund_id IS NOT NULL
            AND NEW.provider_refund_id IS DISTINCT FROM OLD.provider_refund_id THEN
        RAISE EXCEPTION 'refund_allocations_immutable: a share''s provider id never changes once known'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_immutable';
    END IF;
    IF NEW.state <> OLD.state AND OLD.state <> 'PENDING' THEN
        RAISE EXCEPTION 'refund_allocations_legal_transition: share of refund % cannot move from % to %',
                OLD.refund_id, OLD.state, NEW.state
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_legal_transition';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER refund_allocations_guard
    BEFORE UPDATE ON refund_allocations
    FOR EACH ROW EXECUTE FUNCTION refund_allocations_guard_update();

-- The money rules of shares, checked at commit like refunds_within_order, and under the same lock
-- on the order row, so two refunds of one order sizing their shares queue instead of both taking
-- the last of a tender:
--   * a refund's shares add up to the refund, and belong to tenders of its order;
--   * no tender is promised back more than it captured: its shares that haven't failed add up to
--     at most its captured_minor;
--   * a settled refund agrees with its shares: none pending, SUCCEEDED when all succeeded, FAILED
--     when all failed, otherwise PARTIALLY_FAILED with returned_minor what the succeeded ones add
--     up to.
-- A refund with no shares yet (an older binary's, before the worker gives it them) is checked by
-- refunds_within_order alone.
CREATE FUNCTION refund_allocations_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target   uuid;
    r        record;
    s        record;
    over     record;
BEGIN
    IF TG_TABLE_NAME = 'refund_allocations' THEN
        target := NEW.refund_id;
    ELSE
        target := NEW.id;
    END IF;
    SELECT id, order_id, amount_minor, state, returned_minor INTO r FROM refunds WHERE id = target;
    PERFORM 1 FROM orders WHERE id = r.order_id FOR NO KEY UPDATE;

    SELECT count(*) AS n,
           coalesce(sum(a.amount_minor), 0) AS total,
           count(*) FILTER (WHERE a.state = 'PENDING') AS pending,
           count(*) FILTER (WHERE a.state = 'SUCCEEDED') AS succeeded,
           coalesce(sum(a.amount_minor) FILTER (WHERE a.state = 'SUCCEEDED'), 0) AS returned,
           count(*) FILTER (WHERE p.order_id IS DISTINCT FROM r.order_id) AS foreign_shares
        INTO s
        FROM refund_allocations a LEFT JOIN payments p ON p.id = a.payment_id
        WHERE a.refund_id = target;
    IF s.n = 0 THEN
        RETURN NULL;
    END IF;
    IF s.foreign_shares > 0 THEN
        RAISE EXCEPTION 'refund_allocations_own_tenders: refund % has a share on another order''s tender',
                target
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_own_tenders';
    END IF;
    IF s.total <> r.amount_minor THEN
        RAISE EXCEPTION 'refund_allocations_add_up: shares of refund % add up to %, not its %',
                target, s.total, r.amount_minor
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_add_up';
    END IF;

    SELECT p.position, p.captured_minor, sum(a.amount_minor) AS promised INTO over
        FROM payments p JOIN refund_allocations a ON a.payment_id = p.id
        WHERE p.order_id = r.order_id AND a.state <> 'FAILED'
        GROUP BY p.id, p.position, p.captured_minor
        HAVING sum(a.amount_minor) > p.captured_minor
        ORDER BY p.position
        LIMIT 1;
    IF over.position IS NOT NULL THEN
        RAISE EXCEPTION 'refund_allocations_within_tender: tender % of order % is promised % back, over its % captured',
                over.position, r.order_id, over.promised, over.captured_minor
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_within_tender';
    END IF;

    IF r.state <> 'PENDING' AND (s.pending > 0
            OR (r.state = 'SUCCEEDED' AND s.succeeded <> s.n)
            OR (r.state = 'FAILED' AND s.succeeded <> 0)
            OR (r.state = 'PARTIALLY_FAILED'
                AND (s.succeeded IN (0, s.n) OR r.returned_minor <> s.returned))) THEN
        RAISE EXCEPTION 'refund_allocations_settled: refund % is % but its shares are not',
                target, r.state
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refund_allocations_settled';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER refund_allocations_within_refund
    AFTER INSERT OR UPDATE ON refund_allocations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION refund_allocations_check();

CREATE CONSTRAINT TRIGGER refunds_agree_with_allocations
    AFTER UPDATE OF state, returned_minor ON refunds
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION refund_allocations_check();

-- An older binary settles a refund by its state alone. Its refunds only ever have one share, on
-- the first tender, so that share settles the same way, with the refund's provider id or reason.
CREATE FUNCTION refund_allocations_mirror() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE refund_allocations SET
        state = NEW.state,
        provider_refund_id = NEW.provider_refund_id,
        failure_code = NEW.failure_code,
        failure_message = NEW.failure_message,
        updated_at = NEW.updated_at
    WHERE refund_id = NEW.id AND state = 'PENDING';
    RETURN NULL;
END
$$;

CREATE TRIGGER refund_allocations_mirror_settle
    AFTER UPDATE OF state ON refunds
    FOR EACH ROW
    WHEN (current_setting('payflow.refund_writer', true) IS DISTINCT FROM 'allocations'
        AND OLD.state = 'PENDING' AND NEW.state IN ('SUCCEEDED', 'FAILED'))
    EXECUTE FUNCTION refund_allocations_mirror();

-- A partial failure returns only part of the refund, so that part is what counts against the
-- order's capture: its unreturned rest can be refunded again. Its lines still count, because the
-- goods came back. Otherwise unchanged from V9.
CREATE OR REPLACE FUNCTION refunds_check_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target   uuid;
    o        record;
    total    bigint;
    over_sku text;
BEGIN
    IF TG_TABLE_NAME = 'refunds' THEN
        target := NEW.order_id;
    ELSE
        target := NEW.id;
    END IF;
    IF current_setting('transaction_isolation') = 'repeatable read' THEN
        RAISE EXCEPTION 'refunds_isolation: write refunds in READ COMMITTED or SERIALIZABLE'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_isolation';
    END IF;

    SELECT state, captured_minor, items INTO o FROM orders WHERE id = target FOR NO KEY UPDATE;
    SELECT coalesce(sum(CASE WHEN state = 'PARTIALLY_FAILED' THEN returned_minor
                             ELSE amount_minor END), 0) INTO total
        FROM refunds WHERE order_id = target AND state <> 'FAILED';
    IF total = 0 THEN
        RETURN NULL;
    END IF;

    IF o.state NOT IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED') THEN
        RAISE EXCEPTION 'refunds_order_completed: order % is %; only a completed order has refunds',
                target, o.state
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_order_completed';
    END IF;
    IF total > o.captured_minor THEN
        RAISE EXCEPTION 'refunds_within_captured: refunds of order % add up to %, over its % captured',
                target, total, o.captured_minor
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_within_captured';
    END IF;

    SELECT r.sku INTO over_sku
    FROM (SELECT l ->> 'sku' AS sku, sum((l ->> 'quantity')::bigint) AS quantity
          FROM refunds, jsonb_array_elements(lines) AS l
          WHERE order_id = target AND state <> 'FAILED' AND lines IS NOT NULL
          GROUP BY 1) AS r
    LEFT JOIN (SELECT i ->> 'sku' AS sku, sum((i ->> 'quantity')::bigint) AS quantity
               FROM jsonb_array_elements(o.items) AS i
               GROUP BY 1) AS bought USING (sku)
    WHERE r.quantity > coalesce(bought.quantity, 0)
    ORDER BY r.sku
    LIMIT 1;
    IF over_sku IS NOT NULL THEN
        RAISE EXCEPTION 'refunds_within_lines: refunds of order % return more % than it bought',
                target, over_sku
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_within_lines';
    END IF;
    RETURN NULL;
END
$$;

-- refunds_within_order fires on INSERT OR UPDATE of any column already, so a settlement that only
-- records returned_minor is checked too.

-- Every existing refund gets its one share, on its order's first tender (the only one an order
-- made before split tender has), settled as the refund is.
INSERT INTO refund_allocations (refund_id, payment_id, seq, amount_minor, state,
    provider_refund_id, failure_code, failure_message, created_at, updated_at)
SELECT r.id, p.id, 1, r.amount_minor, r.state, r.provider_refund_id,
    CASE WHEN r.state = 'FAILED' THEN coalesce(r.failure_code, 'provider_refused') END,
    r.failure_message, r.created_at, r.updated_at
FROM refunds r JOIN payments p ON p.order_id = r.order_id AND p.position = 1
ON CONFLICT DO NOTHING;
