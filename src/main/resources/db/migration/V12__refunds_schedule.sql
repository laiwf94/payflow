-- Each refund runs on its own schedule and lease, the way an order's saga does: next_attempt_at
-- says when the refund worker should try it, lease_token/lease_until which worker holds it while
-- it talks to the provider, attempt how many tries came before. A refund that failed records why.
--
-- refunds is new in this release and small, so the brief exclusive lock these statements take on
-- it costs nothing; orders is not touched.

ALTER TABLE refunds
    ADD COLUMN attempt         integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN lease_token     uuid,
    ADD COLUMN lease_until     timestamptz,
    ADD COLUMN failure_code    text,
    ADD COLUMN failure_message text;

-- Rows written before this migration: a pending refund is due now, a failed one gets a code.
UPDATE refunds SET next_attempt_at = updated_at WHERE state = 'PENDING';
UPDATE refunds SET failure_code = 'unknown' WHERE state = 'FAILED' AND failure_code IS NULL;

ALTER TABLE refunds
    ADD CONSTRAINT refunds_attempt_nonnegative CHECK (attempt >= 0),
    -- A pending refund is always scheduled, so none can be forgotten; a settled one never is.
    ADD CONSTRAINT refunds_pending_scheduled CHECK ((next_attempt_at IS NULL) = (state <> 'PENDING')),
    ADD CONSTRAINT refunds_lease_pair CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    ADD CONSTRAINT refunds_settled_unleased CHECK (lease_token IS NULL OR state = 'PENDING'),
    ADD CONSTRAINT refunds_failed_explained CHECK (state <> 'FAILED' OR failure_code IS NOT NULL);

-- The money check only depends on which refunds exist and which have failed, so it runs on an
-- insert or a state change, not on every lease the worker takes and gives back.
DROP TRIGGER refunds_within_order ON refunds;
CREATE CONSTRAINT TRIGGER refunds_within_order
    AFTER INSERT OR UPDATE OF state ON refunds
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION refunds_check_order();
