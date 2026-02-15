package com.bidstream.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps the time-partitioned {@code bids} table (PDR §8.3). The composite primary key is
 * {@code (id, created_at)} since Postgres requires the partition column in every unique key.
 */
@Entity
@Table(name = "bids")
@IdClass(BidJpaEntity.BidId.class)
public class BidJpaEntity {

    @jakarta.persistence.Id
    private UUID id;

    @Column(name = "auction_id", nullable = false)
    private UUID auctionId;

    @Column(name = "bidder_id", nullable = false)
    private UUID bidderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @jakarta.persistence.Id
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BidJpaEntity() {
        // JPA
    }

    public BidJpaEntity(UUID id, UUID auctionId, UUID bidderId, BigDecimal amount, String type,
                         String status, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.amount = amount;
        this.type = type;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static final class BidId implements Serializable {
        private UUID id;
        private Instant createdAt;

        public BidId() {
        }

        public BidId(UUID id, Instant createdAt) {
            this.id = id;
            this.createdAt = createdAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BidId bidId)) return false;
            return Objects.equals(id, bidId.id) && Objects.equals(createdAt, bidId.createdAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, createdAt);
        }
    }
}
