-- Event ids a consumer has already applied. Inserting here in the same transaction as the
-- consumer's own write makes redelivery harmless: the second insert conflicts and the event is
-- skipped.

CREATE TABLE inbox (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    received_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

CREATE INDEX inbox_received_at ON inbox (received_at);

-- What the timeline consumer builds from the events: one row per order event, readable by the
-- merchant through the API.
CREATE TABLE order_timeline (
    id            bigserial   PRIMARY KEY,
    order_id      uuid        NOT NULL,
    merchant_id   text        NOT NULL,
    seq           bigint      NOT NULL,
    event_id      uuid        NOT NULL UNIQUE,
    event_type    text        NOT NULL,
    state         text        NOT NULL,
    failure_code  text,
    occurred_at   timestamptz NOT NULL,
    recorded_at   timestamptz NOT NULL,
    CONSTRAINT order_timeline_seq_unique UNIQUE (order_id, seq)
);
