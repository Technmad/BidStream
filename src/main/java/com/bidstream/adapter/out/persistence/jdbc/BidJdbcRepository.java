package com.bidstream.adapter.out.persistence.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL idempotent insert into {@code bids} for the auction-processor's write path (PDR
 * §9.6 rule 2). Separate from {@code BidRepositoryImpl} (used by the Phase-1 synchronous
 * endpoint), which relies on the JPA unique-constraint violation surfacing as an error rather
 * than silently no-op-ing on replay.
 */
@Repository
public class BidJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public BidJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertIfAbsent(UUID id, UUID auctionId, UUID bidderId, BigDecimal amount,
                                String type, String status, String idempotencyKey,
                                Instant occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO bids (id, auction_id, bidder_id, amount, type, status, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (auction_id, bidder_id, idempotency_key, created_at) DO NOTHING
                """,
                id, auctionId, bidderId, amount, type, status, idempotencyKey,
                Timestamp.from(occurredAt));
    }
}
