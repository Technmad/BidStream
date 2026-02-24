package com.bidstream.adapter.in.ws.dto;

import java.time.Instant;
import java.util.UUID;

/** Matches the {@code PRICE_UPDATE} message shape in PDR §15.2. */
public record PriceUpdateMessage(
        String type,
        UUID auctionId,
        String price,
        String winnerId,
        Instant endTime,
        Instant serverNow) {

    public static PriceUpdateMessage of(UUID auctionId, String price, String winnerId, Instant endTime) {
        return new PriceUpdateMessage("PRICE_UPDATE", auctionId, price,
                (winnerId == null || winnerId.isBlank()) ? null : winnerId, endTime, Instant.now());
    }
}
