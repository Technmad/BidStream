-- bids' own UNIQUE (auction_id, bidder_id, idempotency_key, created_at) has to include
-- created_at only because Postgres requires a partitioned table's unique constraints to include
-- the partition key - not because it's semantically part of the identity. Since created_at is
-- the edge-stamped occurredAt, two independent client requests carrying the SAME
-- Idempotency-Key but arriving moments apart get DIFFERENT created_at values, so they never
-- collide on that constraint - it can only catch an exact Kafka redelivery of the identical
-- message, never a genuine second HTTP call with a reused key. This table isn't partitioned, so
-- it can enforce true cross-request uniqueness (see docs/adr/0005).

CREATE TABLE bid_idempotency_keys (
    auction_id      UUID NOT NULL,
    bidder_id       UUID NOT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (auction_id, bidder_id, idempotency_key)
);
