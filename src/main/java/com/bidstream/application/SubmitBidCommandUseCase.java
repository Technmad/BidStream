package com.bidstream.application;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.domain.port.EventPublisher;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Edge-side half of the async bid pipeline (PDR §9.1, §14.2): stamps {@code eventId} and
 * {@code occurredAt} once, here, and publishes onto {@code auction.commands} keyed by
 * {@code auctionId} so Kafka orders it against every other command for that auction. Not yet
 * wired to the public REST endpoint — that cutover happens once the processor, dedup ledger,
 * and outbox relay are all in place (see the async bid-endpoint branch later in Phase 2).
 */
@Service
public class SubmitBidCommandUseCase {

    private static final String TOPIC = "auction.commands";

    private final EventPublisher eventPublisher;

    public SubmitBidCommandUseCase(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public BidCommand submit(UUID auctionId, UUID bidderId, BigDecimal amount, String currency,
                              String idempotencyKey) {
        BidCommand command = BidCommand.of(auctionId, bidderId, amount, currency, idempotencyKey);
        eventPublisher.publish(TOPIC, auctionId.toString(), command);
        return command;
    }
}
