package com.bidstream.adapter.out.persistence.jdbc;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to {@code processed_events}, since the idempotent {@code ON CONFLICT DO
 * NOTHING} insert (PDR §9.6 rule 2) has no clean JPA equivalent.
 */
@Repository
public class ProcessedEventJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProcessedEventRecord> findById(UUID eventId) {
        return jdbcTemplate.query("""
                SELECT event_id, auction_id, outcome, reject_reason, final_price, winner_id, occurred_at
                  FROM processed_events WHERE event_id = ?
                """,
                (rs, rowNum) -> new ProcessedEventRecord(
                        UUID.fromString(rs.getString("event_id")),
                        UUID.fromString(rs.getString("auction_id")),
                        rs.getString("outcome"),
                        rs.getString("reject_reason"),
                        rs.getBigDecimal("final_price"),
                        rs.getString("winner_id") == null ? null : UUID.fromString(rs.getString("winner_id")),
                        rs.getTimestamp("occurred_at").toInstant()),
                eventId)
                .stream().findFirst();
    }

    /** Idempotent insert - a redelivered command re-inserting the same event_id is a no-op. */
    public void insertIfAbsent(ProcessedEventRecord record) {
        jdbcTemplate.update("""
                INSERT INTO processed_events
                    (event_id, auction_id, outcome, reject_reason, final_price, winner_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                record.eventId(), record.auctionId(), record.outcome(), record.rejectReason(),
                record.finalPrice(), record.winnerId(), Timestamp.from(record.occurredAt()));
    }
}
