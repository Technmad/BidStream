package com.bidstream.adapter.out.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0004 flagged outbox pruning as unimplemented (QA-REVIEW.md Medium): unlike
 * {@code processed_events}, nothing ever deleted an {@code outbox} row, so it grew unbounded.
 * Runs against the local dev stack's real Postgres (docker/docker-compose.yml).
 */
@SpringBootTest
class OutboxJdbcRepositoryIT {

    @Autowired
    private OutboxJdbcRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pruneOlderThanDeletesOnlyStalePublishedRowsNeverUnpublishedOnes() {
        UUID stalePublished = insertOutboxRow(Instant.now().minus(30, ChronoUnit.DAYS));
        UUID freshPublished = insertOutboxRow(Instant.now());
        UUID staleUnpublished = insertUnpublishedOutboxRow(Instant.now().minus(30, ChronoUnit.DAYS));

        int pruned = repository.pruneOlderThan(7);

        assertThat(pruned).isGreaterThanOrEqualTo(1);
        assertThat(rowExists(stalePublished)).isFalse();
        assertThat(rowExists(freshPublished)).isTrue();
        // No matter how old, a row the relay hasn't confirmed publishing yet must survive.
        assertThat(rowExists(staleUnpublished)).isTrue();

        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (?, ?)", freshPublished, staleUnpublished);
    }

    private UUID insertOutboxRow(Instant publishedAt) {
        UUID aggregateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox (aggregate_id, topic, partition_key, payload, published_at)
                VALUES (?, 'test.topic', ?, '{}'::jsonb, ?)
                """, aggregateId, aggregateId.toString(), Timestamp.from(publishedAt));
        return aggregateId;
    }

    private UUID insertUnpublishedOutboxRow(Instant createdAt) {
        UUID aggregateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox (aggregate_id, topic, partition_key, payload, created_at, published_at)
                VALUES (?, 'test.topic', ?, '{}'::jsonb, ?, NULL)
                """, aggregateId, aggregateId.toString(), Timestamp.from(createdAt));
        return aggregateId;
    }

    private boolean rowExists(UUID aggregateId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, aggregateId);
        return count != null && count > 0;
    }
}
