-- Domain events, written in the same transaction as the order change they describe and relayed
-- to Kafka afterwards. aggregate_seq is the order's version at the time of the change, so events
-- for one order sort in the order they happened.

CREATE TABLE outbox (
    id              bigserial   PRIMARY KEY,
    event_id        uuid        NOT NULL UNIQUE,
    aggregate_id    uuid        NOT NULL,
    aggregate_seq   bigint      NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    correlation_id  text        NOT NULL,
    created_at      timestamptz NOT NULL,
    published_at    timestamptz,
    CONSTRAINT outbox_seq_unique UNIQUE (aggregate_id, aggregate_seq)
);

-- The relay's working set: unpublished events, looked up by order.
CREATE INDEX outbox_unpublished ON outbox (aggregate_id, aggregate_seq) WHERE published_at IS NULL;
CREATE INDEX outbox_unpublished_id ON outbox (id) WHERE published_at IS NULL;
CREATE INDEX outbox_published_at ON outbox (published_at) WHERE published_at IS NOT NULL;
