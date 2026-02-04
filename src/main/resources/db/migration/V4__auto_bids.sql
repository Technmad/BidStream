CREATE TABLE auto_bids (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id UUID NOT NULL REFERENCES auctions(id),
    bidder_id  UUID NOT NULL REFERENCES users(id),
    max_amount NUMERIC(19,4) NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (auction_id, bidder_id)
);

CREATE INDEX idx_autobids_auction_active ON auto_bids(auction_id) WHERE active;
