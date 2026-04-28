package com.bidstream.application;

import com.bidstream.adapter.out.persistence.jdbc.WatchJdbcRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PDR §14.4: a durable bookmark, deliberately decoupled from live WebSocket delivery (§15.1) -
 * this never subscribes anyone to anything, it only ever writes/deletes one row the caller owns.
 */
@Service
public class WatchService {

    private final WatchJdbcRepository watchRepository;
    private final AuctionService auctionService;

    public WatchService(WatchJdbcRepository watchRepository, AuctionService auctionService) {
        this.watchRepository = watchRepository;
        this.auctionService = auctionService;
    }

    @Transactional
    public void watch(UUID auctionId, UUID userId) {
        auctionService.getById(auctionId); // throws NotFoundException for an unknown auction
        watchRepository.watch(userId, auctionId);
    }

    @Transactional
    public void unwatch(UUID auctionId, UUID userId) {
        // No existence check - unwatching is unconditionally idempotent, including for an
        // auction id that never existed at all; there's nothing to 404 on for a delete that was
        // always going to be a no-op either way.
        watchRepository.unwatch(userId, auctionId);
    }
}
