package com.bidstream.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auctions")
public class AuctionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "starting_price", nullable = false)
    private BigDecimal startingPrice;

    @Column(name = "reserve_price")
    private BigDecimal reservePrice;

    @Column(name = "min_increment", nullable = false)
    private BigDecimal minIncrement;

    @Column(name = "current_price", nullable = false)
    private BigDecimal currentPrice;

    @Column(name = "current_winner_id")
    private UUID currentWinnerId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String status;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "anti_snipe_seconds", nullable = false)
    private int antiSnipeSeconds;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuctionJpaEntity() {
        // JPA
    }

    public AuctionJpaEntity(UUID id, UUID sellerId, UUID categoryId, String title,
                             String description, BigDecimal startingPrice, BigDecimal reservePrice,
                             BigDecimal minIncrement, BigDecimal currentPrice, UUID currentWinnerId,
                             String currency, String status, Instant startTime, Instant endTime,
                             int antiSnipeSeconds, long version, Instant createdAt) {
        this.id = id;
        this.sellerId = sellerId;
        this.categoryId = categoryId;
        this.title = title;
        this.description = description;
        this.startingPrice = startingPrice;
        this.reservePrice = reservePrice;
        this.minIncrement = minIncrement;
        this.currentPrice = currentPrice;
        this.currentWinnerId = currentWinnerId;
        this.currency = currency;
        this.status = status;
        this.startTime = startTime;
        this.endTime = endTime;
        this.antiSnipeSeconds = antiSnipeSeconds;
        this.version = version;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getStartingPrice() {
        return startingPrice;
    }

    public BigDecimal getReservePrice() {
        return reservePrice;
    }

    public BigDecimal getMinIncrement() {
        return minIncrement;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public UUID getCurrentWinnerId() {
        return currentWinnerId;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public int getAntiSnipeSeconds() {
        return antiSnipeSeconds;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
