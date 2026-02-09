package com.bidstream.domain.port;

import com.bidstream.domain.model.AuctionItem;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the {@code AuctionItem} aggregate. Implemented by a JPA adapter
 * (adapter/out/persistence); the domain and application layers depend only on this interface.
 */
public interface AuctionRepository {

    AuctionItem save(AuctionItem auction);

    Optional<AuctionItem> findById(UUID id);

    /**
     * Persists the aggregate only if its stored {@code version} still matches the version this
     * instance was loaded with (optimistic-lock backstop, PDR §9.2). Returns {@code false} if
     * another writer moved first.
     */
    boolean saveWithOptimisticLock(AuctionItem auction, long expectedVersion);
}
