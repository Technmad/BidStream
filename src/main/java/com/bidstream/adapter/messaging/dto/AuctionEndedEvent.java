package com.bidstream.adapter.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Matches the {@code AUCTION_ENDED} lifecycle message in PDR §15.2 / the auctions.events topic. */
public record AuctionEndedEvent(
        UUID eventId,
        int schemaVersion,
        UUID auctionId,
        String outcome,
        UUID winnerId,
        BigDecimal finalPrice,
        Instant occurredAt,
        UUID correlationId) {
}
