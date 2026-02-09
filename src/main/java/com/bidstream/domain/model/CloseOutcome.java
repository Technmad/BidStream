package com.bidstream.domain.model;

import java.util.UUID;

/**
 * The result of {@link AuctionItem#close}. See PDR §9 / §11.3.
 */
public sealed interface CloseOutcome {

    record Sold(UUID winnerId, Money finalPrice) implements CloseOutcome {
    }

    record Unsold() implements CloseOutcome {
    }

    /** The close was stale (auction already closed, or extended past the scheduled end). */
    record Ignored(String reason) implements CloseOutcome {
    }
}
