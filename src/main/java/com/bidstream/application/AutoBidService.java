package com.bidstream.application;

import com.bidstream.adapter.in.kafka.AuctionWorkingSet;
import com.bidstream.common.ConflictException;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AuctionStatus;
import com.bidstream.domain.model.AutoBid;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.BidType;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import com.bidstream.domain.port.AutoBidRepository;
import com.bidstream.domain.service.AutoBidResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages standing proxy-bid instructions (PDR §12, §14.1). Setting a new auto-bid is itself a
 * ladder event - PDR §12.1 says the ladder resolves "when a new bid (manual amount, or a new
 * auto-bid with max) arrives" - so this resolves immediately against the current price and any
 * existing leader, exactly like a manual bid would, rather than waiting for one.
 *
 * <p>Unlike a manual bid, this writes directly to Postgres rather than publishing onto
 * {@code auction.commands}: it's a lower-volume, lower-contention action, and the processor
 * always re-reads active auto-bids fresh from committed Postgres before resolving the next
 * manual bid for this auction, so there's no race that matters for correctness.
 */
@Service
public class AutoBidService {

    private final AuctionRepository auctionRepository;
    private final AutoBidRepository autoBidRepository;
    private final AcceptedBidPersister acceptedBidPersister;
    private final AuctionWorkingSet workingSet;

    public AutoBidService(AuctionRepository auctionRepository, AutoBidRepository autoBidRepository,
                           AcceptedBidPersister acceptedBidPersister, AuctionWorkingSet workingSet) {
        this.auctionRepository = auctionRepository;
        this.autoBidRepository = autoBidRepository;
        this.acceptedBidPersister = acceptedBidPersister;
        this.workingSet = workingSet;
    }

    @Transactional
    public AutoBid setAutoBid(UUID auctionId, UUID bidderId, Money maxAmount) {
        AuctionItem auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new NotFoundException("Auction not found: " + auctionId));
        if (auction.status() != AuctionStatus.OPEN && auction.status() != AuctionStatus.EXTENDED) {
            throw new ConflictException("Auction is not open for bidding");
        }
        if (auction.sellerId().equals(bidderId)) {
            throw new ConflictException("Sellers cannot bid on their own auctions");
        }

        var existing = autoBidRepository.findByAuctionIdAndBidderId(auctionId, bidderId);
        Instant createdAt = existing.map(AutoBid::createdAt).orElseGet(Instant::now);
        AutoBid autoBid = autoBidRepository.save(new AutoBid(
                existing.map(AutoBid::id).orElseGet(UUID::randomUUID),
                auctionId, bidderId, maxAmount, true, createdAt));

        resolveAgainstCurrentState(auction, auctionId, bidderId, maxAmount, createdAt);
        return autoBid;
    }

    @Transactional
    public void cancelAutoBid(UUID auctionId, UUID bidderId) {
        autoBidRepository.deactivate(auctionId, bidderId);
    }

    /**
     * Resolves the just-set auto-bid as a challenger against whatever currently leads (another
     * auto-bid, or nobody), and applies the result if it changes anything.
     */
    private void resolveAgainstCurrentState(AuctionItem auction, UUID auctionId, UUID bidderId,
                                             Money maxAmount, Instant createdAt) {
        long expectedVersion = auction.version();
        Money priceBeforeThisEvent = auction.currentPrice();

        List<AutoBid> otherActive = autoBidRepository.findActiveByAuctionId(auctionId).stream()
                .filter(ab -> !ab.bidderId().equals(bidderId))
                .toList();
        AutoBid existingLeader = otherActive.stream()
                .max((a, b) -> {
                    int cmp = a.maxAmount().compareTo(b.maxAmount());
                    return cmp != 0 ? cmp : b.createdAt().compareTo(a.createdAt());
                })
                .orElse(null);

        AutoBidResolver.Leader leader = existingLeader == null ? null
                : new AutoBidResolver.Leader(existingLeader.bidderId(), existingLeader.maxAmount(),
                        existingLeader.createdAt());
        AutoBidResolver.Resolution resolution = AutoBidResolver.resolve(priceBeforeThisEvent,
                auction.minIncrement(), leader, new AutoBidResolver.Challenger(bidderId, maxAmount, createdAt));

        // Also skip if this bidder is already exactly where they'd resolve to (idempotent re-set).
        if (resolution.winnerId().equals(auction.currentWinnerId())
                && resolution.price().equals(priceBeforeThisEvent)) {
            return;
        }

        BidOutcome.Accepted accepted = auction.applyResolvedBid(resolution.winnerId(), resolution.price(),
                Instant.now());

        boolean persisted = auctionRepository.saveWithOptimisticLock(auction, expectedVersion);
        if (!persisted) {
            throw new ConflictException("Auction changed concurrently - please retry setting your auto-bid");
        }
        // This write bypassed the processor's working set entirely - evict so the next command
        // for this auction reseeds fresh from the committed Postgres row we just wrote, rather
        // than deciding against a stale in-memory copy (PDR §9.6's phantom-price trap).
        workingSet.evict(auctionId);

        acceptedBidPersister.persist(auctionId, resolution.winnerId(), resolution.price().amount(),
                BidType.AUTO.name(), "auto:" + UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
                accepted, UUID.randomUUID(), UUID.randomUUID());
    }
}
