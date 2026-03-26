package com.bidstream.adapter.out.persistence.jdbc;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL idempotent insert into {@code settlements} (PDR §11.3): the unique constraint on
 * {@code auction_id} makes a duplicate CLOSE a no-op rather than an error.
 */
@Repository
public class SettlementJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public SettlementJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertIfAbsent(UUID auctionId, UUID winnerId, BigDecimal finalPrice, String outcome) {
        jdbcTemplate.update("""
                INSERT INTO settlements (auction_id, winner_id, final_price, outcome)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (auction_id) DO NOTHING
                """,
                auctionId, winnerId, finalPrice, outcome);
    }
}
