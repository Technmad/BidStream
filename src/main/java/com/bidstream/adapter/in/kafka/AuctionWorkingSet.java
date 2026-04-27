package com.bidstream.adapter.in.kafka;

import com.bidstream.domain.model.AuctionItem;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The processor's bounded, replayable working set (PDR §9.1, §9.6): an LRU of recently-active
 * auctions, each entry either committed-Postgres state or committed-plus-un-flushed-deltas no
 * more than one message ahead of the last committed offset. Since every message for a given
 * auction lands on the same partition (and therefore the same consumer thread), concurrent
 * access here is always to <em>different</em> keys from different threads — a synchronized map
 * is sufficient and never contends on a single auction.
 *
 * <p>Capacity is bounded so a long-running processor doesn't accumulate unbounded auction state;
 * an evicted (or never-seeded) entry is transparently reloaded from committed Postgres on next
 * touch, which is exactly what makes eviction safe.
 *
 * <p><b>QA-REVIEW.md Low:</b> that eviction safety currently relies on every entry always being
 * fully flushed to Postgres before this map could ever evict it - true today because
 * {@link com.bidstream.application.AuctionCommandProcessor} commits each command's write in its
 * own transaction with no cross-message buffering. §9.6's per-partition batch flush (buffering
 * several commands' writes before committing) is a deliberately deferred optimization, not yet
 * implemented - and the moment it is, this LRU needs eviction-pinning for any entry with
 * un-flushed deltas, or a batch flush racing an eviction could silently lose writes. Don't add
 * batching here without adding that pinning in the same change.
 */
@Component
public class AuctionWorkingSet {

    private static final int MAX_ENTRIES = 1000;

    private final Map<UUID, AuctionItem> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, AuctionItem> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public AuctionItem getOrSeed(UUID auctionId, Supplier<AuctionItem> seedFromCommittedPostgres) {
        return entries.computeIfAbsent(auctionId, id -> seedFromCommittedPostgres.get());
    }

    public void put(UUID auctionId, AuctionItem auction) {
        entries.put(auctionId, auction);
    }

    public void evict(UUID auctionId) {
        entries.remove(auctionId);
    }

    int size() {
        return entries.size();
    }
}
