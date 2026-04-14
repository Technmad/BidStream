package com.bidstream.adapter.out.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the partition-creation DDL and processed_events pruning DML against the local dev
 * stack's real Postgres (docker/docker-compose.yml) - both are exactly the kind of
 * DDL-with-side-effects that's worth proving against a real catalog, not a mock.
 */
@SpringBootTest
class PartitionMaintenanceJdbcRepositoryIT {

    private static final YearMonth TEST_MONTH = YearMonth.of(2031, 7);

    @Autowired
    private PartitionMaintenanceJdbcRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void dropTestPartition() {
        // This DDL is real and persists in the shared dev database across runs - leaving it
        // behind would make the next run's "doesn't exist yet" assumption false.
        jdbcTemplate.execute("DROP TABLE IF EXISTS bids_2031_07");
    }

    @Test
    void createPartitionIfAbsentIsIdempotentAndCreatesAQueryableChildTable() {
        // Far enough in the future that no earlier migration/scheduler run could already own it.
        assertThat(repository.partitionExists(TEST_MONTH)).isFalse();

        repository.createPartitionIfAbsent(TEST_MONTH);
        assertThat(repository.partitionExists(TEST_MONTH)).isTrue();

        // A second call for the same month must be a harmless no-op, not an error.
        repository.createPartitionIfAbsent(TEST_MONTH);
        assertThat(repository.partitionExists(TEST_MONTH)).isTrue();

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bids_2031_07", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void pruneProcessedEventsOlderThanDeletesOnlyStaleRows() {
        UUID staleEventId = UUID.randomUUID();
        UUID freshEventId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        insertProcessedEvent(staleEventId, auctionId, Instant.now().minus(30, ChronoUnit.DAYS));
        insertProcessedEvent(freshEventId, auctionId, Instant.now());

        int pruned = repository.pruneProcessedEventsOlderThan(15);

        assertThat(pruned).isGreaterThanOrEqualTo(1);
        assertThat(repository.partitionExists(YearMonth.now())).isTrue(); // sanity: repository still usable
        Integer staleRemaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, staleEventId);
        Integer freshRemaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, freshEventId);
        assertThat(staleRemaining).isZero();
        assertThat(freshRemaining).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM processed_events WHERE event_id = ?", freshEventId);
    }

    private void insertProcessedEvent(UUID eventId, UUID auctionId, Instant occurredAt) {
        jdbcTemplate.update("""
                INSERT INTO processed_events (event_id, auction_id, outcome, occurred_at)
                VALUES (?, ?, 'ACCEPTED', ?)
                """, eventId, auctionId, Timestamp.from(occurredAt));
    }
}
