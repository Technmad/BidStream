package com.bidstream.adapter.out.persistence.jdbc;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to {@code bid_idempotency_keys} - the true, atomic, cross-request duplicate
 * guard {@code bids}' own unique constraint can't provide (its partition-key requirement forces
 * {@code created_at} into the constraint, so it only catches an exact Kafka redelivery, never a
 * genuine second client request reusing a key - see docs/adr/0005).
 */
@Repository
public class BidIdempotencyKeyJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public BidIdempotencyKeyJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Claims the key, permanently, regardless of what happens to the bid afterwards - mirrors
     * {@code IdempotencyKeyGuard}'s own fast-path semantics (claimed once, never released; a
     * client must mint a new key for a genuine new attempt).
     *
     * @return {@code true} if this is the first time this key has been seen (safe to proceed)
     */
    public boolean claim(UUID auctionId, UUID bidderId, String idempotencyKey) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO bid_idempotency_keys (auction_id, bidder_id, idempotency_key)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                auctionId, bidderId, idempotencyKey);
        return inserted == 1;
    }
}
