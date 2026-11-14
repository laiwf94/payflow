-- Replays and test pings for webhook deliveries.
--
-- A replayed delivery is sent again with a retry window of its own, counted from replayed_at
-- rather than from when the event happened, so a replay of a week-old event still gets its full
-- days of retries. Its attempt count and attempt log carry on from before the replay.
--
-- A ping is a delivery that belongs to no order, so order_id and order_seq become optional; the
-- CHECK keeps them set on every other delivery. It is added NOT VALID and validated in V20 under a
-- weaker lock, as V5 and V6 do for orders.
--
-- Adding a nullable column with no default and dropping NOT NULL only change the catalog, so this
-- holds its ACCESS EXCLUSIVE lock on webhook_deliveries for an instant and rewrites nothing.
ALTER TABLE webhook_deliveries ADD COLUMN replayed_at timestamptz;
ALTER TABLE webhook_deliveries ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE webhook_deliveries ALTER COLUMN order_seq DROP NOT NULL;
ALTER TABLE webhook_deliveries ADD CONSTRAINT webhook_deliveries_order CHECK (
    CASE WHEN event_type = 'ping' THEN order_id IS NULL AND order_seq IS NULL
         ELSE order_id IS NOT NULL AND order_seq IS NOT NULL END) NOT VALID;
