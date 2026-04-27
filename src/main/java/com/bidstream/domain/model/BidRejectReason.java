package com.bidstream.domain.model;

/**
 * Reasons a command may be rejected, whether by {@link AuctionItem#placeBid} or by a guard the
 * processor runs before it (e.g. {@code DUPLICATE_IDEMPOTENCY_KEY}). Persisted verbatim on the
 * {@code processed_events} ledger row for a rejected command (PDR §8, §9.6).
 */
public enum BidRejectReason {
    AUCTION_NOT_OPEN,
    BELOW_MIN_INCREMENT,
    SELF_BID,
    ALREADY_HIGHEST,
    AUCTION_ENDED,
    STALE_VERSION,
    RATE_LIMITED,
    DUPLICATE_IDEMPOTENCY_KEY,
    INVALID_BID
}
