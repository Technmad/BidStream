-- PDR §8.4/§14.4: a durable bookmark, not a live-delivery mechanism. Idempotent by construction:
-- watching twice writes the same row (PK conflict -> ON CONFLICT DO NOTHING), unwatching
-- something never-watched deletes zero rows. Neither is an error - both are legitimate outcomes
-- of a client that doesn't track local watch-state precisely and just calls the endpoint to be
-- sure. Not part of the bid-processing replay path (PDR §9.6), so an ordinary DEFAULT now() is
-- exactly right here.
CREATE TABLE watches (
    user_id     UUID NOT NULL REFERENCES users(id),
    auction_id  UUID NOT NULL REFERENCES auctions(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, auction_id)
);

CREATE INDEX idx_watches_auction ON watches(auction_id);
