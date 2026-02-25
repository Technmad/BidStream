package com.bidstream.adapter.out.persistence.jdbc;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to the transactional outbox (PDR §10.3). Rows are inserted inside the same
 * transaction as the state change they describe; {@link #findUnpublished} / {@link #markPublished}
 * back the relay poller that ships them to Kafka afterwards.
 */
@Repository
public class OutboxJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(UUID aggregateId, String topic, String partitionKey, String jsonPayload) {
        jdbcTemplate.update("""
                INSERT INTO outbox (aggregate_id, topic, partition_key, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                aggregateId, topic, partitionKey, jsonPayload);
    }

    /**
     * {@code FOR UPDATE SKIP LOCKED} lets several relay instances (one per app node) poll
     * concurrently without fighting over the same rows.
     */
    public List<OutboxRow> findUnpublished(int limit) {
        return jdbcTemplate.query("""
                SELECT id, topic, partition_key, payload::text AS payload
                  FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OutboxRow(rs.getLong("id"), rs.getString("topic"),
                        rs.getString("partition_key"), rs.getString("payload")),
                limit);
    }

    public void markPublished(long id) {
        jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE id = ?", id);
    }

    public record OutboxRow(long id, String topic, String partitionKey, String payload) {
    }
}
