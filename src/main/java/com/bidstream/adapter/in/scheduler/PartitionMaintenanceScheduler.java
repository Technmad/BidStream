package com.bidstream.adapter.in.scheduler;

import com.bidstream.adapter.out.cache.RedisLeaderLock;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.PartitionMaintenanceJdbcRepository;
import java.time.Duration;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@code bids} range-partitioned table (PDR §8) ahead of the clock and keeps
 * {@code processed_events} and (QA-REVIEW.md Medium; ADR-0004) {@code outbox} from growing
 * unbounded. All three operations are idempotent DDL/DML, so - like {@link CloseTriggerScheduler}
 * - the Redis lock here is purely an efficiency measure to stop every app replica from racing the
 * same {@code CREATE TABLE}/{@code DELETE}; it is never a correctness dependency.
 */
@Component
public class PartitionMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceScheduler.class);
    private static final String LOCK_KEY = "lock:partition-maintenance-scheduler";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final PartitionMaintenanceJdbcRepository repository;
    private final OutboxJdbcRepository outboxRepository;
    private final RedisLeaderLock leaderLock;
    private final int processedEventsRetentionDays;
    private final int outboxRetentionDays;

    public PartitionMaintenanceScheduler(PartitionMaintenanceJdbcRepository repository,
                                          OutboxJdbcRepository outboxRepository,
                                          RedisLeaderLock leaderLock,
                                          @Value("${bidstream.processed-events.retention-days:15}")
                                          int processedEventsRetentionDays,
                                          @Value("${bidstream.outbox.retention-days:7}")
                                          int outboxRetentionDays) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.leaderLock = leaderLock;
        this.processedEventsRetentionDays = processedEventsRetentionDays;
        this.outboxRetentionDays = outboxRetentionDays;
    }

    @Scheduled(cron = "${bidstream.partition-scheduler.cron:0 0 2 * * *}")
    public void ensureUpcomingPartitionsAndPruneProcessedEvents() {
        String token = leaderLock.tryAcquire(LOCK_KEY, LOCK_TTL);
        if (token == null) {
            return; // another instance holds the lock this tick
        }
        try {
            YearMonth now = YearMonth.now();
            // Create this month's and next month's partitions - the lead time absorbs a missed
            // run (the scheduler being down for a day, a clock skew) without ever risking a bid
            // landing with no partition to go in.
            for (YearMonth month : new YearMonth[] {now, now.plusMonths(1)}) {
                if (!repository.partitionExists(month)) {
                    repository.createPartitionIfAbsent(month);
                    log.info("Created bids partition for {}", month);
                }
            }

            int pruned = repository.pruneProcessedEventsOlderThan(processedEventsRetentionDays);
            if (pruned > 0) {
                log.info("Pruned {} processed_events rows older than {} days", pruned, processedEventsRetentionDays);
            }

            int outboxPruned = outboxRepository.pruneOlderThan(outboxRetentionDays);
            if (outboxPruned > 0) {
                log.info("Pruned {} published outbox rows older than {} days", outboxPruned, outboxRetentionDays);
            }
        } finally {
            leaderLock.release(LOCK_KEY, token);
        }
    }
}
