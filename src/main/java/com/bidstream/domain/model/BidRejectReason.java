package com.bidstream.domain.model;

/**
 * Reasons a command may be rejected by {@link AuctionItem#placeBid}. Persisted verbatim on the
 * {@code processed_events} ledger row for a rejected command (PDR §8, §9.6).
 */
public enum BidRejectReason {
    AUCTION_NOT_OPEN,
    BELOW_MIN_INCREMENT,
    SELF_BID,
    ALREADY_HIGHEST,
    AUCTION_ENDED,
    STALE_VERSION,
    RATE_LIMITED
}
