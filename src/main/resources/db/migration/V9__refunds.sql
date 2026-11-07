-- One row per refund of an order, partial or full. A refund is either an amount or a set of
-- lines (sku and quantity, whose stock comes back); amount_minor is what it returns to the
-- customer either way. A PENDING refund already counts against the order: it is money promised
-- back, and two refunds racing each other must not both be promised the last of it.
--
-- The new table takes no lock on orders beyond the catalog entry for the foreign key, so this
-- migration is safe while orders are being taken.

CREATE TABLE refunds (
    id                 uuid        PRIMARY KEY,
    order_id           uuid        NOT NULL REFERENCES orders (id),
    amount_minor       bigint      NOT NULL,
    lines              jsonb,
    state              text        NOT NULL,
    provider_refund_id text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,

    CONSTRAINT refunds_amount_range CHECK (amount_minor BETWEEN 1 AND 99999999),
    CONSTRAINT refunds_known_state CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- A non-empty array of {"sku": string, "quantity": integer >= 1}. A path predicate over a
    -- missing key is false, so a line without either key is caught too.
    CONSTRAINT refunds_lines_format CHECK (lines IS NULL OR (
        jsonb_typeof(lines) = 'array' AND jsonb_array_length(lines) > 0
        AND NOT jsonb_path_exists(lines, '$[*] ? (!(@.type() == "object"
            && @.sku.type() == "string" && @.quantity.type() == "number"
            && @.quantity >= 1 && @.quantity == @.quantity.floor()))'))),
    -- The provider's id is what a later lookup matches on, so a refund can't succeed without it.
    CONSTRAINT refunds_succeeded_identified CHECK (
        state <> 'SUCCEEDED' OR provider_refund_id IS NOT NULL)
);

-- An order's refunds, oldest first: the listing, and the sums the trigger below takes.
CREATE INDEX refunds_order ON refunds (order_id, created_at);

-- A refund's order, amount, lines and creation time never change, and its state moves only out
-- of PENDING (to SUCCEEDED or FAILED). The provider's id, once known, is kept.
CREATE FUNCTION refunds_guard_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.order_id <> OLD.order_id OR NEW.amount_minor <> OLD.amount_minor
            OR NEW.lines IS DISTINCT FROM OLD.lines OR NEW.created_at <> OLD.created_at
            OR NEW.id <> OLD.id THEN
        RAISE EXCEPTION 'refunds_immutable: a refund''s order, amount and lines never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_immutable';
    END IF;
    IF OLD.provider_refund_id IS NOT NULL
            AND NEW.provider_refund_id IS DISTINCT FROM OLD.provider_refund_id THEN
        RAISE EXCEPTION 'refunds_immutable: a refund''s provider id never changes once known'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_immutable';
    END IF;
    IF NEW.state <> OLD.state AND OLD.state <> 'PENDING' THEN
        RAISE EXCEPTION 'refunds_legal_transition: refund % cannot move from % to %',
                OLD.id, OLD.state, NEW.state
            USING ERRCODE = 'check_violation', CONSTRAINT = 'refunds_legal_transition';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER refunds_guard
    BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION refunds_guard_update();

-- The money invariant: an order's refunds that haven't failed never add up to more than it
-- captured, and never return more of a sku than the order bought. It is checked at commit, so a
-- transaction may write the refund and the order in either order. To hold under concurrency the
-- check first locks the order row: two transactions refunding one order queue on that lock, and
-- in READ COMMITTED each statement after it takes a fresh snapshot, so the second sees the
-- first's committed refund. The lock is FOR NO KEY UPDATE because each refund's foreign key
-- already holds FOR KEY SHARE on the order, which FOR UPDATE would wait on: two racing refunds
-- would deadlock instead of queueing. SERIALIZABLE is safe by itself. REPEATABLE READ is refused:
-- its snapshot predates the lock, so it would sum without the other refund. Each message starts
-- with the rule's name, as Postgres names the constraint a row violates.
CREATE FUNCTION refunds_check_order() RETURNS trigger LANGUAGE plpgsql AS $$
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
    SELECT coalesce(sum(amount_minor), 0) INTO total
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

CREATE CONSTRAINT TRIGGER refunds_within_order
    AFTER INSERT OR UPDATE ON refunds
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION refunds_check_order();

-- The same check when the order side moves: a capture that shrinks, or an order with refunds
-- leaving the refundable states. The WHEN keeps it off every saga step before completion, which
-- only ever raise captured_minor and never start from those states.
CREATE CONSTRAINT TRIGGER orders_within_refunds
    AFTER UPDATE OF captured_minor, state ON orders
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.captured_minor < OLD.captured_minor
          OR (NEW.state IS DISTINCT FROM OLD.state
              AND OLD.state IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')))
    EXECUTE FUNCTION refunds_check_order();
