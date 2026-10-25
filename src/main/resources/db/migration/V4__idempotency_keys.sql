-- Idempotency keys for POST requests, per merchant. A row is either in progress (holds a lock
-- token until locked_until) or completed (holds the response to replay).

CREATE TABLE idempotency_keys (
    merchant_id        text        NOT NULL,
    idem_key           text        NOT NULL,
    request_method     text        NOT NULL,
    request_path       text        NOT NULL,
    request_hash       bytea       NOT NULL,
    lock_token         uuid,
    locked_until       timestamptz,
    response_status    integer,
    response_body      text,
    response_location  text,
    created_at         timestamptz NOT NULL,
    completed_at       timestamptz,
    PRIMARY KEY (merchant_id, idem_key),
    CONSTRAINT idempotency_keys_state CHECK (
        (lock_token IS NOT NULL AND locked_until IS NOT NULL AND completed_at IS NULL
            AND response_status IS NULL)
        OR (lock_token IS NULL AND locked_until IS NULL AND completed_at IS NOT NULL
            AND response_status IS NOT NULL))
);

CREATE INDEX idempotency_keys_created_at ON idempotency_keys (created_at);
