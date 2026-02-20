package com.bidstream.adapter.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire format for a BID command on {@code auction.commands} (PDR §10.2). Shared between the
 * producer (edge, stamps eventId/occurredAt) and the consumer (auction-processor).
 */
public record BidCommand(
        UUID eventId,
        int schemaVersion,
        String commandType,
        UUID auctionId,
        UUID bidderId,
        BigDecimal amount,
        String currency,
        String type,
        String idempotencyKey,
        Instant occurredAt,
        UUID correlationId) {

    public static final String COMMAND_TYPE = "BID";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static BidCommand of(UUID auctionId, UUID bidderId, BigDecimal amount, String currency,
                                 String idempotencyKey) {
        return new BidCommand(
                UUID.randomUUID(), CURRENT_SCHEMA_VERSION, COMMAND_TYPE, auctionId, bidderId,
                amount, currency, "MANUAL", idempotencyKey, Instant.now(), UUID.randomUUID());
    }
}
