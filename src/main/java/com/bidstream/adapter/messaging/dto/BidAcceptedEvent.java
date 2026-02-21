package com.bidstream.adapter.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Matches the {@code bids.accepted} schema in PDR §10.2. */
public record BidAcceptedEvent(
        UUID eventId,
        int schemaVersion,
        UUID auctionId,
        UUID bidId,
        UUID bidderId,
        BigDecimal amount,
        UUID previousWinnerId,
        Instant newEndTime,
        Instant occurredAt,
        UUID correlationId) {
}
