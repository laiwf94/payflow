-- The payment steps now write each tender's row themselves, in the transaction of the order's own
-- fenced write, so the mirror of V30 must stop overwriting what they record: the provider id a
-- reversal found for an authorization whose answer was lost, a tender voided rather than
-- derived from the failure code, and every tender after the first.
--
-- Expand before contract, still: a binary from before this migration keeps writing only the
-- order row during a rolling upgrade, and its single-tender orders still need their tender kept
-- in step. So the mirror stays, and this binary switches it off for each transaction that writes
-- an order, by setting payflow.payment_writer to 'tenders' with set_config(..., true) in the
-- statement itself (see OrderRepository). A transaction-local setting costs no extra round trip
-- and resets at commit, so a pooled connection never carries it into an older binary's work.
-- An older binary never sets it, so its writes are mirrored exactly as before.
--
-- The insert trigger is unchanged: every new order still gets one pending tender for its whole
-- amount from the database, whichever binary inserts it, and an order paid with several tenders
-- reshapes that row into the first and adds the rest in the same transaction.
--
-- The mirror also leaves an order with more than one tender alone: only this binary makes those,
-- and position 1 is no longer the whole order.
--
-- CREATE OR REPLACE TRIGGER takes a brief SHARE ROW EXCLUSIVE lock on orders and nothing else.

CREATE OR REPLACE FUNCTION payments_mirror_single_tender() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM payments WHERE order_id = NEW.id AND position > 1) THEN
        RETURN NULL;
    END IF;
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

CREATE OR REPLACE TRIGGER payments_mirror_update
    AFTER UPDATE ON orders
    FOR EACH ROW
    WHEN (current_setting('payflow.payment_writer', true) IS DISTINCT FROM 'tenders'
        AND (OLD.state, OLD.payment_id, OLD.captured_minor, OLD.refunded_minor,
            OLD.authorization_expires_at, OLD.reauthorized_at, OLD.failure_code)
        IS DISTINCT FROM (NEW.state, NEW.payment_id, NEW.captured_minor, NEW.refunded_minor,
            NEW.authorization_expires_at, NEW.reauthorized_at, NEW.failure_code))
    EXECUTE FUNCTION payments_mirror_single_tender();
