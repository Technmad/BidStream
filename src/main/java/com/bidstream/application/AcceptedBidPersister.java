package com.bidstream.application;

import com.bidstream.adapter.messaging.dto.BidAcceptedEvent;
import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.out.persistence.jdbc.BidJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.port.LeaderboardCache;
import com.bidstream.domain.port.PriceCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The three durable side-effects of any accepted bid - manual, or an auto-bid's own opening
 * claim or counter-bid (PDR §12.1: "each resolution step emits the appropriate events so the
 * ladder is fully recorded in history") - factored out since both the processor and
 * {@link AutoBidService} need to apply them identically.
 */
@Component
public class AcceptedBidPersister {

    private static final String BIDS_ACCEPTED_TOPIC = "bids.accepted";

    private final PriceCache priceCache;
    private final LeaderboardCache leaderboardCache;
    private final BidJdbcRepository bidJdbcRepository;
    private final OutboxJdbcRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AcceptedBidPersister(PriceCache priceCache, LeaderboardCache leaderboardCache,
                                 BidJdbcRepository bidJdbcRepository,
                                 OutboxJdbcRepository outboxRepository, ObjectMapper objectMapper) {
        this.priceCache = priceCache;
        this.leaderboardCache = leaderboardCache;
        this.bidJdbcRepository = bidJdbcRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void persist(UUID auctionId, UUID bidderId, BigDecimal amount, String type,
                         String idempotencyKey, Instant occurredAt, UUID bidId,
                         BidOutcome.Accepted accepted, UUID eventId, UUID correlationId) {
        // Project to Redis synchronously, before the durable writes (PDR §9.6 step 3) - the
        // ticker (Phase 3) reads this, never the DB, for broadcast.
        priceCache.setCurrent(auctionId, accepted.newPrice(), accepted.newWinnerId(), accepted.newEndTime());
        priceCache.markDirty(auctionId);
        leaderboardCache.recordBid(auctionId, bidderId, amount);

        bidJdbcRepository.insertIfAbsent(bidId, auctionId, bidderId, amount, type, "ACCEPTED",
                idempotencyKey, occurredAt);

        try {
            String json = objectMapper.writeValueAsString(new BidAcceptedEvent(
                    eventId, BidCommand.CURRENT_SCHEMA_VERSION, auctionId, bidId, bidderId,
                    amount, accepted.previousWinnerId(), accepted.newEndTime(), accepted.extended(),
                    occurredAt, correlationId));
            outboxRepository.insert(auctionId, BIDS_ACCEPTED_TOPIC, auctionId.toString(), json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize bids.accepted outbox event", e);
        }
    }
}
