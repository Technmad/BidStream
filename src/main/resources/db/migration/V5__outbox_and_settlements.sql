CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  UUID NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    partition_key VARCHAR(80)  NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;

CREATE TABLE settlements (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id   UUID NOT NULL UNIQUE REFERENCES auctions(id),
    winner_id    UUID REFERENCES users(id),
    final_price  NUMERIC(19,4),
    outcome      VARCHAR(10) NOT NULL,
    settled_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
