package com.bidstream.adapter.out.persistence.jdbc;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to the transactional outbox (PDR §10.3). Rows written here are inserted
 * inside the same transaction as the state change they describe, then relayed to Kafka
 * separately (the relay is added in the write-behind-batching branch).
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
}
