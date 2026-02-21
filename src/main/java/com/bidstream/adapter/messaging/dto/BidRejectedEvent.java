package com.bidstream.adapter.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Client feedback for a rejected bid (PDR §10.1 {@code bids.rejected}). */
public record BidRejectedEvent(
        UUID eventId,
        int schemaVersion,
        UUID auctionId,
        UUID bidderId,
        String reason,
        BigDecimal currentPrice,
        BigDecimal minIncrement,
        Instant occurredAt,
        UUID correlationId) {
}
