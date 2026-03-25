package com.bidstream.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auto_bids")
public class AutoBidJpaEntity {

    @Id
    private UUID id;

    @Column(name = "auction_id", nullable = false)
    private UUID auctionId;

    @Column(name = "bidder_id", nullable = false)
    private UUID bidderId;

    @Column(name = "max_amount", nullable = false)
    private BigDecimal maxAmount;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AutoBidJpaEntity() {
        // JPA
    }

    public AutoBidJpaEntity(UUID id, UUID auctionId, UUID bidderId, BigDecimal maxAmount,
                             boolean active, Instant createdAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.maxAmount = maxAmount;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuctionId() {
        return auctionId;
    }

    public UUID getBidderId() {
        return bidderId;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
