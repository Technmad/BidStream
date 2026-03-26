package com.bidstream.adapter.messaging.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format for a CLOSE command on {@code auction.commands} (PDR §10.2). Keyed by
 * {@code auctionId} exactly like {@link BidCommand}, so it lands on the same partition and is
 * ordered against every bid by the same single writer (PDR §11.3) - this is what makes
 * "did this bid beat the close?" a trivial ordering question rather than a clock comparison.
 */
public record CloseCommand(
        UUID eventId,
        int schemaVersion,
        String commandType,
        UUID auctionId,
        Instant scheduledEndTime,
        Instant occurredAt,
        UUID correlationId) {

    public static final String COMMAND_TYPE = "CLOSE";

    public static CloseCommand of(UUID auctionId, Instant scheduledEndTime) {
        return new CloseCommand(UUID.randomUUID(), BidCommand.CURRENT_SCHEMA_VERSION, COMMAND_TYPE,
                auctionId, scheduledEndTime, Instant.now(), UUID.randomUUID());
    }
}
