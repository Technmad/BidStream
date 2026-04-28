-- PDR §8.4/§14.4: basic keyword search. GENERATED so it can never drift from title/description -
-- no separate write path to keep in sync, no reindex job. GIN gives real indexed lookup; a
-- leading-wildcard LIKE/ILIKE can't use a standard B-tree index and becomes a full sequential
-- scan the moment the catalog grows. Deliberately no ranking (ts_rank) or synonym handling -
-- filtering only, matching the boundary PDR §2.2 draws around relevance engines.
ALTER TABLE auctions ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''))
    ) STORED;

CREATE INDEX idx_auctions_search ON auctions USING GIN (search_vector);
