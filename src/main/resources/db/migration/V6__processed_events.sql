-- Dedup ledger + stored outcome (PDR §8, v1.3). The authority for "have I already
-- processed command E?" -- written inside the same flush transaction as the bid/auction
-- writes it accompanies, so its presence is atomic with them.
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    auction_id    UUID NOT NULL,
    outcome       VARCHAR(16) NOT NULL,
    reject_reason VARCHAR(24),
    final_price   NUMERIC(19,4),
    winner_id     UUID,
    occurred_at   TIMESTAMPTZ NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_events_occurred ON processed_events(occurred_at);
