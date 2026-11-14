-- Signing secret rotation, and endpoints that payflow disables after they fail for days.
--
-- Rotation: the endpoint's current secret moves into the previous_secret_* columns and a new one
-- takes its place. Until previous_secret_expires_at both sign every delivery, as two v1 values in
-- one Payflow-Signature header, so a merchant can deploy the new secret without dropping events.
-- The previous secret is sealed exactly as the current one is (security.SecretBox, bound to the
-- endpoint and merchant), so rotating moves the ciphertext between columns without opening it.
-- Expired previous secrets are wiped by the dispatcher.
--
-- Health: failing_since is when the endpoint's current run of failed attempts began; a 2xx clears
-- it. An endpoint failing for the configured time (3 days) is disabled with disabled_reason
-- 'failing' and sent a final notice. A merchant who disables an endpoint leaves 'merchant'. The
-- reason is read only while the endpoint is DISABLED, and no CHECK ties it to the state: an
-- instance still on the previous version changes state without knowing the column exists, and its
-- writes must keep working during a rolling upgrade.
--
-- Adding nullable columns without defaults changes only the catalog. webhook_endpoints holds at
-- most 16 live rows per merchant, so its CHECKs are validated here; the deliveries CHECK is added
-- NOT VALID and validated in V22 under a weaker lock, as V19 and V20 did.
ALTER TABLE webhook_endpoints
    ADD COLUMN previous_secret_key_id     text,
    ADD COLUMN previous_secret_nonce      bytea,
    ADD COLUMN previous_secret_ciphertext bytea,
    ADD COLUMN previous_secret_expires_at timestamptz,
    ADD COLUMN failing_since              timestamptz,
    ADD COLUMN disabled_reason            text;

-- A previous secret is whole or absent. Deleting an endpoint wipes it (an older instance's delete
-- doesn't know the columns, so the dispatcher's sweep wipes what such a delete leaves).
ALTER TABLE webhook_endpoints ADD CONSTRAINT webhook_endpoints_previous_secret CHECK (
    (previous_secret_key_id IS NULL AND previous_secret_nonce IS NULL
         AND previous_secret_ciphertext IS NULL AND previous_secret_expires_at IS NULL)
    OR (previous_secret_key_id IS NOT NULL AND length(previous_secret_nonce) = 12
         AND length(previous_secret_ciphertext) > 16 AND previous_secret_expires_at IS NOT NULL));
ALTER TABLE webhook_endpoints ADD CONSTRAINT webhook_endpoints_disabled_reason CHECK (
    disabled_reason IS NULL OR disabled_reason IN ('merchant', 'failing'));

-- The final notice to a disabled endpoint, like a ping, belongs to no order.
ALTER TABLE webhook_deliveries DROP CONSTRAINT webhook_deliveries_order;
ALTER TABLE webhook_deliveries ADD CONSTRAINT webhook_deliveries_order CHECK (
    CASE WHEN event_type IN ('ping', 'webhook_endpoint.disabled')
         THEN order_id IS NULL AND order_seq IS NULL
         ELSE order_id IS NOT NULL AND order_seq IS NOT NULL END) NOT VALID;
