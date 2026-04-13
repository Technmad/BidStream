package com.bidstream.adapter.out.persistence.jdbc;

import java.time.YearMonth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native-DDL access for the {@code bids} monthly range-partition maintenance job (PDR §8.3).
 * Table/bound names are built only from a {@link YearMonth} the caller derives from the clock,
 * never from user input, so string-built DDL here carries no injection risk.
 */
@Repository
public class PartitionMaintenanceJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public PartitionMaintenanceJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean partitionExists(YearMonth month) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = ?",
                Integer.class, partitionName(month));
        return count != null && count > 0;
    }

    /** {@code CREATE TABLE ... IF NOT EXISTS} - safe to call every tick even once the partition exists. */
    public void createPartitionIfAbsent(YearMonth month) {
        YearMonth next = month.plusMonths(1);
        String ddl = "CREATE TABLE IF NOT EXISTS %s PARTITION OF bids FOR VALUES FROM ('%s-01') TO ('%s-01')"
                .formatted(partitionName(month), month, next);
        jdbcTemplate.execute(ddl);
    }

    private String partitionName(YearMonth month) {
        return "bids_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    /** PDR §8.3: retain {@code processed_events} ~8 days past Kafka's own 7-day retention. */
    public int pruneProcessedEventsOlderThan(int retentionDays) {
        return jdbcTemplate.update(
                "DELETE FROM processed_events WHERE occurred_at < now() - (? || ' days')::interval",
                retentionDays);
    }
}
