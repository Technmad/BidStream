package com.bidstream.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The result of {@link AuctionItem#placeBid}. Sealed so callers must handle both branches.
 */
public sealed interface BidOutcome {

    record Accepted(UUID previousWinnerId, UUID newWinnerId, Money newPrice, boolean extended,
                     Instant newEndTime) implements BidOutcome {
    }

    record Rejected(BidRejectReason reason) implements BidOutcome {
    }

    static Accepted accepted(UUID previousWinnerId, UUID newWinnerId, Money newPrice,
                              boolean extended, Instant newEndTime) {
        return new Accepted(previousWinnerId, newWinnerId, newPrice, extended, newEndTime);
    }

    static Rejected rejected(BidRejectReason reason) {
        return new Rejected(reason);
    }
}
