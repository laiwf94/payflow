-- Gives every order that existed before V30 its payment row, with the same values V30's trigger
-- writes: one tender at position 1 for the whole amount, through the primary provider, its state
-- read off the order, and the keys the saga has been sending for it all along, so a step that
-- resumes after the upgrade still finds its earlier calls.
--
-- In batches of 2,000 orders, each committed on its own (V31__backfill_payments.sql.conf runs it
-- outside a transaction), so no lock is held over the whole table and a large backfill doesn't
-- stall checkout. Orders are walked by id, which needs no new index. Writes during the backfill
-- are safe either way: the trigger and this both upsert on (order_id, position), the trigger with
-- the order's current values and this only where no row exists yet. If it stops part way, the
-- next start runs it again and it carries on, skipping orders that already have their row.

DO $$
DECLARE
    last_id uuid := '00000000-0000-0000-0000-000000000000';
    batch   uuid[];
BEGIN
    LOOP
        SELECT array_agg(id ORDER BY id) INTO batch
        FROM (SELECT id FROM orders WHERE id > last_id ORDER BY id LIMIT 2000) b;
        EXIT WHEN batch IS NULL;

        INSERT INTO payments (id, order_id, position, method, provider, amount_minor, currency,
            state, provider_payment_id, authorize_key, capture_key, reauthorize_key,
            captured_minor, refunded_minor, authorization_expires_at, reauthorized_at,
            created_at, updated_at)
        SELECT gen_random_uuid(), o.id, 1, o.payment_method, 'primary', o.amount_minor,
            o.currency,
            payments_single_tender_state(o.state, o.payment_id, o.captured_minor,
                o.refunded_minor, o.failure_code),
            o.payment_id, 'authorize-' || o.id, 'capture-' || o.id, 'reauthorize-' || o.id,
            o.captured_minor, o.refunded_minor, o.authorization_expires_at, o.reauthorized_at,
            o.created_at, o.updated_at
        FROM orders o
        WHERE o.id = ANY (batch)
        ON CONFLICT (order_id, position) DO NOTHING;

        last_id := batch[array_upper(batch, 1)];
        COMMIT;
    END LOOP;
END
$$;
