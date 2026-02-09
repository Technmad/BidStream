package com.bidstream.domain.port;

import com.bidstream.domain.model.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Port for the Redis-backed hot-price projection (PDR §13). Rebuildable from Postgres at any
 * time — never a source of truth. Implemented by a Redis adapter (adapter/out/cache) in Phase 3.
 */
public interface PriceCache {

    void setCurrent(UUID auctionId, Money price, UUID winnerId, Instant endTime);

    void markDirty(UUID auctionId);
}
