package com.bidstream.application;

import com.bidstream.common.ConflictException;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.Bid;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.BidStatus;
import com.bidstream.domain.model.BidType;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import com.bidstream.domain.port.BidRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase-1 synchronous bid placement: validates and commits directly against Postgres, with the
 * {@code version} column as the concurrency guard (PDR §9.2 backstop). This is a stepping stone
 * — Phase 2 replaces it with the async Kafka pipeline (§9.1) where a single per-auction writer
 * makes contention structurally impossible rather than retried. The optimistic-lock retry loop
 * here is exactly the kind of contention that per-auction partitioning is designed to eliminate.
 */
@Service
public class PlaceBidUseCase {

    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;

    public PlaceBidUseCase(AuctionRepository auctionRepository, BidRepository bidRepository) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
    }

    @Transactional
    public PlaceBidResult execute(UUID auctionId, UUID bidderId, Money amount,
                                   String idempotencyKey) {
        if (bidRepository.existsByIdempotencyKey(auctionId, bidderId, idempotencyKey)) {
            throw new ConflictException("Duplicate bid: idempotency key already used");
        }

        for (int attempt = 0; attempt < MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            var auction = auctionRepository.findById(auctionId)
                    .orElseThrow(() -> new NotFoundException("Auction not found: " + auctionId));
            long expectedVersion = auction.version();

            Instant now = Instant.now();
            BidOutcome outcome = auction.placeBid(bidderId, amount, now);

            if (outcome instanceof BidOutcome.Rejected rejected) {
                return new PlaceBidResult(rejected, null, auction.currentPrice(),
                        auction.minIncrement());
            }

            boolean persisted = auctionRepository.saveWithOptimisticLock(auction, expectedVersion);
            if (!persisted) {
                // Someone else moved first (PDR §9.2) - reload and reprocess rather than
                // blindly overwrite. Structurally rare outside genuine concurrent hot bidding.
                continue;
            }

            Bid bid = new Bid(UUID.randomUUID(), auctionId, bidderId, amount, BidType.MANUAL,
                    BidStatus.ACCEPTED, idempotencyKey, now);
            Bid saved = bidRepository.save(bid);

            return new PlaceBidResult(outcome, saved.id(), auction.currentPrice(),
                    auction.minIncrement());
        }

        throw new ConflictException(
                "Could not place bid after " + MAX_OPTIMISTIC_LOCK_RETRIES + " attempts due to contention");
    }

    public record PlaceBidResult(BidOutcome outcome, UUID bidId, Money currentPrice,
                                  Money minIncrement) {
    }
}
