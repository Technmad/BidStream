package com.bidstream.adapter.in.ws.dto;

import java.time.Instant;
import java.util.UUID;

/** Matches the {@code AUCTION_EXTENDED} message shape in PDR §15.2. */
public record AuctionExtendedMessage(
        String type,
        UUID auctionId,
        Instant newEndTime,
        Instant serverNow) {

    public static AuctionExtendedMessage of(UUID auctionId, Instant newEndTime) {
        return new AuctionExtendedMessage("AUCTION_EXTENDED", auctionId, newEndTime, Instant.now());
    }
}
