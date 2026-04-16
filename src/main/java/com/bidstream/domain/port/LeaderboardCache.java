package com.bidstream.domain.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Port for the per-auction leaderboard projection (PDR §13:
 * {@code auction:{id}:leaderboard} - a sorted set scored by each bidder's own highest bid).
 * Rebuildable from Postgres at any time - never a source of truth.
 */
public interface LeaderboardCache {

    void recordBid(UUID auctionId, UUID bidderId, BigDecimal amount);

    List<Entry> topN(UUID auctionId, int count);

    record Entry(UUID bidderId, BigDecimal amount) {
    }
}
