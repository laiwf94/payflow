-- Re-authorization before an authorization lapses. A manual-capture order waits for the
-- merchant's capture for as long as its authorization lives; shortly before the hold lapses a
-- timer sets reauthorization_requested, and the saga extends the authorization once, recording
-- when in reauthorized_at and the new expiry in authorization_expires_at. Only a manual order can
-- be asked to re-authorize, and only one that was asked can have been re-authorized.
--
-- As in V26: a constant default rewrites nothing, and the checks are added NOT VALID so ADD
-- COLUMN's ACCESS EXCLUSIVE lock isn't held over a full-table scan; V29 validates them under a
-- lock that lets reads and writes continue. Every existing row has the defaults, which pass.

ALTER TABLE orders
    ADD COLUMN reauthorization_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN reauthorized_at timestamptz,
    ADD CONSTRAINT orders_reauthorization_manual CHECK (
        NOT reauthorization_requested OR capture_mode = 'manual') NOT VALID,
    ADD CONSTRAINT orders_reauthorized_requested CHECK (
        reauthorized_at IS NULL
        OR (reauthorization_requested AND isfinite(reauthorized_at))) NOT VALID;
