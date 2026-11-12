-- Merchant webhooks: the endpoints a merchant registers, one delivery per event and endpoint, and
-- one row per attempt to deliver it. The three tables are new, so this migration takes no lock on
-- any table that orders are written to and is safe while orders are being taken.

-- An endpoint's signing secret is stored encrypted (AES-256-GCM, see security.SecretBox): the
-- nonce, the ciphertext with its tag, and the id of the key that sealed it, so keys can rotate
-- while old rows stay readable. A deleted endpoint keeps its row for its delivery history, but
-- its secret is wiped.
CREATE TABLE webhook_endpoints (
    id                uuid        PRIMARY KEY,
    merchant_id       text        NOT NULL,
    url               text        NOT NULL,
    description       text,
    enabled_events    text[]      NOT NULL,
    state             text        NOT NULL,
    secret_key_id     text,
    secret_nonce      bytea,
    secret_ciphertext bytea,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    deleted_at        timestamptz,

    CONSTRAINT webhook_endpoints_known_state CHECK (state IN ('ENABLED', 'DISABLED')),
    -- The API checks the URL properly; this keeps a raw write from storing something that is
    -- plainly not an http(s) URL, or one long enough to bloat every delivery log row.
    CONSTRAINT webhook_endpoints_url_format CHECK (
        url ~ '^https?://[^\s/?#@]+([/?][^\s#]*)?$' AND length(url) <= 2048),
    CONSTRAINT webhook_endpoints_description_length CHECK (
        description IS NULL OR length(description) BETWEEN 1 AND 256),
    -- One to 32 types; '*' means every type and stands alone.
    CONSTRAINT webhook_endpoints_events_count CHECK (
        cardinality(enabled_events) BETWEEN 1 AND 32
        AND array_position(enabled_events, NULL) IS NULL),
    CONSTRAINT webhook_endpoints_events_wildcard CHECK (
        NOT ('*' = ANY (enabled_events)) OR cardinality(enabled_events) = 1),
    -- A live endpoint always has a whole secret; a deleted one has none.
    CONSTRAINT webhook_endpoints_secret CHECK (
        (deleted_at IS NULL AND secret_key_id IS NOT NULL AND length(secret_nonce) = 12
             AND length(secret_ciphertext) > 16)
        OR (deleted_at IS NOT NULL AND secret_key_id IS NULL AND secret_nonce IS NULL
             AND secret_ciphertext IS NULL))
);

-- A merchant's live endpoints, oldest first: the listing, and the count the per-merchant limit
-- takes.
CREATE INDEX webhook_endpoints_merchant ON webhook_endpoints (merchant_id, created_at, id)
    WHERE deleted_at IS NULL;

-- One delivery of one event to one endpoint. It carries the event's payload because the outbox
-- row it came from is deleted once relayed, and a delivery must still be replayable after that.
-- order_id and order_seq are the event's order and its place in that order's stream, so a
-- dispatcher can keep one order's events in order per endpoint.
CREATE TABLE webhook_deliveries (
    id              uuid        PRIMARY KEY,
    endpoint_id     uuid        NOT NULL REFERENCES webhook_endpoints (id),
    event_id        uuid        NOT NULL,
    event_type      text        NOT NULL,
    order_id        uuid        NOT NULL,
    order_seq       bigint      NOT NULL,
    payload         jsonb       NOT NULL,
    state           text        NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,

    CONSTRAINT webhook_deliveries_once UNIQUE (endpoint_id, event_id),
    CONSTRAINT webhook_deliveries_known_state CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT webhook_deliveries_attempts_nonnegative CHECK (attempts >= 0),
    -- A pending delivery is always scheduled, so none can be forgotten; a settled one never is.
    CONSTRAINT webhook_deliveries_pending_scheduled CHECK (
        (next_attempt_at IS NULL) = (state <> 'PENDING'))
);

-- What is due, across every endpoint.
CREATE INDEX webhook_deliveries_due ON webhook_deliveries (next_attempt_at)
    WHERE state = 'PENDING';
-- An endpoint's delivery log, newest first, and one order's deliveries in stream order.
CREATE INDEX webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id, created_at, id);
CREATE INDEX webhook_deliveries_order ON webhook_deliveries (endpoint_id, order_id, order_seq);

-- Every attempt to deliver, kept so a merchant can see each one: when, how long, what came back.
-- An attempt that got no HTTP answer (refused, timed out, address not allowed) has no status and
-- says why in error. The response body is kept only in part.
CREATE TABLE webhook_attempts (
    id              bigserial   PRIMARY KEY,
    delivery_id     uuid        NOT NULL REFERENCES webhook_deliveries (id),
    attempted_at    timestamptz NOT NULL,
    latency_ms      integer     NOT NULL,
    response_status integer,
    response_body   text,
    error           text,

    CONSTRAINT webhook_attempts_latency CHECK (latency_ms >= 0),
    CONSTRAINT webhook_attempts_status CHECK (response_status BETWEEN 100 AND 599),
    CONSTRAINT webhook_attempts_outcome CHECK ((response_status IS NULL) <> (error IS NULL)),
    CONSTRAINT webhook_attempts_body_length CHECK (
        response_body IS NULL OR length(response_body) <= 1024),
    CONSTRAINT webhook_attempts_error_length CHECK (error IS NULL OR length(error) <= 512)
);

CREATE INDEX webhook_attempts_delivery ON webhook_attempts (delivery_id, id);
