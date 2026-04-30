package com.bidstream.adapter.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire format for a SET_AUTO_BID command on {@code auction.commands} (PDR §10.2, ADR-0002).
 * Keyed by {@code auctionId} exactly like {@link BidCommand}, so setting an auto-bid resolves
 * against the auction aggregate through the same single writer as every manual bid, rather than
 * racing it with a direct write from the HTTP thread.
 *
 * <p>The standing {@code auto_bids} row itself is saved synchronously by
 * {@code AutoBidService} before this command is published - only the ladder resolution against
 * the auction's price/winner (which mutates the {@code AuctionItem} aggregate) needs to go
 * through the single-writer pipeline. {@code autoBidCreatedAt} carries the saved row's
 * {@code createdAt} so the processor's tie-break resolution uses the exact same instant.
 */
public record SetAutoBidCommand(
        UUID eventId,
        int schemaVersion,
        String commandType,
        UUID auctionId,
        UUID bidderId,
        BigDecimal maxAmount,
        String currency,
        Instant autoBidCreatedAt,
        Instant occurredAt,
        UUID correlationId) {

    public static final String COMMAND_TYPE = "SET_AUTO_BID";

    public static SetAutoBidCommand of(UUID auctionId, UUID bidderId, BigDecimal maxAmount,
                                        String currency, Instant autoBidCreatedAt) {
        return new SetAutoBidCommand(UUID.randomUUID(), BidCommand.CURRENT_SCHEMA_VERSION, COMMAND_TYPE,
                auctionId, bidderId, maxAmount, currency, autoBidCreatedAt, Instant.now(), UUID.randomUUID());
    }
}
