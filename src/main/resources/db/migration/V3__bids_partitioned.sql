-- Append-only history of ACCEPTED bids only. Time-partitioned for archival (PDR §8.3).
-- created_at is the command's occurredAt (app-supplied), NEVER DEFAULT now() -- see PDR §8
-- for why: this is what makes the idempotency guard survive a Kafka replay.
CREATE TABLE bids (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    auction_id      UUID NOT NULL REFERENCES auctions(id),
    bidder_id       UUID NOT NULL REFERENCES users(id),
    amount          NUMERIC(19,4) NOT NULL,
    type            VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    status          VARCHAR(10) NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (id, created_at),
    UNIQUE (auction_id, bidder_id, idempotency_key, created_at)
) PARTITION BY RANGE (created_at);

-- Pre-create the partitions we need now; a maintenance job pre-creates future months (§8.3 / Phase 5).
CREATE TABLE bids_2026_02 PARTITION OF bids FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE bids_2026_03 PARTITION OF bids FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE INDEX idx_bids_auction_time ON bids(auction_id, created_at DESC);
CREATE INDEX idx_bids_bidder       ON bids(bidder_id);
