package com.bidstream.adapter.in.ws.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Matches the {@code AUCTION_ENDED} message shape in PDR §15.2. */
public record AuctionEndedMessage(
        String type,
        UUID auctionId,
        String outcome,
        UUID winnerId,
        BigDecimal finalPrice,
        Instant serverNow) {

    public static AuctionEndedMessage of(UUID auctionId, String outcome, UUID winnerId, BigDecimal finalPrice) {
        return new AuctionEndedMessage("AUCTION_ENDED", auctionId, outcome, winnerId, finalPrice, Instant.now());
    }
}
