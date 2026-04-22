package com.bidstream.application;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.out.cache.EdgeBidPreCheck;
import com.bidstream.common.BidRejectedException;
import com.bidstream.domain.model.BidRejectReason;
import com.bidstream.domain.port.EventPublisher;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Edge-side half of the async bid pipeline (PDR §9.1, §14.2): stamps {@code eventId} and
 * {@code occurredAt} once, here, and publishes onto {@code auction.commands} keyed by
 * {@code auctionId} so Kafka orders it against every other command for that auction.
 */
@Service
public class SubmitBidCommandUseCase {

    private static final String TOPIC = "auction.commands";

    private final EventPublisher eventPublisher;
    private final EdgeBidPreCheck edgeBidPreCheck;

    public SubmitBidCommandUseCase(EventPublisher eventPublisher, EdgeBidPreCheck edgeBidPreCheck) {
        this.eventPublisher = eventPublisher;
        this.edgeBidPreCheck = edgeBidPreCheck;
    }

    public BidCommand submit(UUID auctionId, UUID bidderId, BigDecimal amount, String currency,
                              String idempotencyKey) {
        // Cheap shed of obviously-invalid load (PDR §9.3) - a hint only; the processor remains
        // the authority, so a stale/missing Redis value never blocks a plausible bid.
        if (edgeBidPreCheck.check(auctionId, amount) == EdgeBidPreCheck.Result.OBVIOUSLY_TOO_LOW) {
            throw new BidRejectedException(BidRejectReason.BELOW_MIN_INCREMENT);
        }

        BidCommand command = BidCommand.of(auctionId, bidderId, amount, currency, idempotencyKey);
        eventPublisher.publish(TOPIC, auctionId.toString(), command);
        return command;
    }
}
