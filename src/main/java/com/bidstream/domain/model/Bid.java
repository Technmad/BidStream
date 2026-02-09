package com.bidstream.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An accepted bid. Immutable once written — the {@code bids} table holds only ACCEPTED bids
 * and is append-only (PDR §8.2); rejections are recorded in {@code processed_events} instead.
 */
public final class Bid {

    private final UUID id;
    private final UUID auctionId;
    private final UUID bidderId;
    private final Money amount;
    private final BidType type;
    private final BidStatus status;
    private final String idempotencyKey;
    private final Instant createdAt;

    public Bid(UUID id, UUID auctionId, UUID bidderId, Money amount, BidType type,
               BidStatus status, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.type = type;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
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

    public Money amount() {
        return amount;
    }

    public BidType type() {
        return type;
    }

    public BidStatus status() {
        return status;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
