package com.bidstream.adapter.out.persistence.jdbc;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to {@code watches} (PDR §8.4/§14.4) - a plain transactional insert/delete
 * against Postgres, never replayed off a Kafka log, so it needs none of the domain-aggregate
 * machinery {@code AuctionItem} carries for the bid-processing path.
 */
@Repository
public class WatchJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public WatchJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Idempotent - watching twice writes the same row, never an error or a duplicate. */
    public void watch(UUID userId, UUID auctionId) {
        jdbcTemplate.update("""
                INSERT INTO watches (user_id, auction_id)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
                userId, auctionId);
    }

    /** Idempotent - unwatching something never watched deletes zero rows, never a 404. */
    public void unwatch(UUID userId, UUID auctionId) {
        jdbcTemplate.update("DELETE FROM watches WHERE user_id = ? AND auction_id = ?", userId, auctionId);
    }
}
