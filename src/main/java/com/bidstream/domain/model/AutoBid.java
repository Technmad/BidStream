package com.bidstream.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A standing proxy-bid instruction: "keep me winning up to {@code maxAmount}" (PDR §12).
 */
public final class AutoBid {

    private final UUID id;
    private final UUID auctionId;
    private final UUID bidderId;
    private final Money maxAmount;
    private final boolean active;
    private final Instant createdAt;

    public AutoBid(UUID id, UUID auctionId, UUID bidderId, Money maxAmount, boolean active,
                    Instant createdAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.maxAmount = maxAmount;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID auctionId() {
        return auctionId;
    }

    public UUID bidderId() {
        return bidderId;
    }

    public Money maxAmount() {
        return maxAmount;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
