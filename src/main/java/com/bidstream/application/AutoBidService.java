package com.bidstream.application;

import com.bidstream.adapter.messaging.dto.SetAutoBidCommand;
import com.bidstream.common.ConflictException;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AuctionStatus;
import com.bidstream.domain.model.AutoBid;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import com.bidstream.domain.port.AutoBidRepository;
import com.bidstream.domain.port.EventPublisher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages standing proxy-bid instructions (PDR §12, §14.1). Setting a new auto-bid is itself a
 * ladder event - PDR §12.1 says the ladder resolves "when a new bid (manual amount, or a new
 * auto-bid with max) arrives" - so it must resolve against the current price and any existing
 * leader exactly like a manual bid would.
 *
 * <p>The standing {@code auto_bids} row is a dedicated table with no contention from the
 * auction-processor, so it's still saved directly here for an immediate HTTP response. The
 * ladder resolution itself mutates the {@code AuctionItem} aggregate (price/winner/version),
 * which per ADR-0002 has exactly one legitimate writer: {@link AuctionCommandProcessor},
 * consuming {@code auction.commands} on the partition keyed by {@code auctionId}. This
 * publishes a {@link SetAutoBidCommand} onto that same topic/partition instead of writing to
 * the auction row from this HTTP thread, so it can never race a concurrent manual bid's
 * optimistic-lock write and cascade to the DLQ.
 */
@Service
public class AutoBidService {

    private static final String TOPIC = "auction.commands";

    private final AuctionRepository auctionRepository;
    private final AutoBidRepository autoBidRepository;
    private final EventPublisher eventPublisher;

    public AutoBidService(AuctionRepository auctionRepository, AutoBidRepository autoBidRepository,
                           EventPublisher eventPublisher) {
        this.auctionRepository = auctionRepository;
        this.autoBidRepository = autoBidRepository;
        this.eventPublisher = eventPublisher;
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

        // The ladder resolution (which may change the auction's price/winner) is decided by the
        // single-writer processor, exactly like a manual bid - never here.
        SetAutoBidCommand command = SetAutoBidCommand.of(auctionId, bidderId, maxAmount.amount(),
                maxAmount.currency().getCurrencyCode(), createdAt);
        eventPublisher.publish(TOPIC, auctionId.toString(), command);

        return autoBid;
    }

    @Transactional
    public void cancelAutoBid(UUID auctionId, UUID bidderId) {
        autoBidRepository.deactivate(auctionId, bidderId);
    }
}
