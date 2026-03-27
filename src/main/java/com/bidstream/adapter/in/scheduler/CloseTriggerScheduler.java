package com.bidstream.adapter.in.scheduler;

import com.bidstream.adapter.messaging.dto.CloseCommand;
import com.bidstream.adapter.out.cache.RedisLeaderLock;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.port.AuctionRepository;
import com.bidstream.domain.port.EventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The close-trigger scheduler (PDR §11.3). May be sloppy without affecting correctness: if it
 * fires a second late, fires twice, or two instances briefly overlap, none of it matters - the
 * CLOSE command it enqueues is ordered on the auction's own partition and handled idempotently
 * by the single writer (§11.3, §19). The Redis lock here is purely an efficiency measure so N
 * app replicas don't all enqueue the same CLOSE, never a correctness dependency.
 */
@Component
public class CloseTriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(CloseTriggerScheduler.class);
    private static final String LOCK_KEY = "lock:close-scheduler";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final String TOPIC = "auction.commands";

    private final AuctionRepository auctionRepository;
    private final EventPublisher eventPublisher;
    private final RedisLeaderLock leaderLock;

    public CloseTriggerScheduler(AuctionRepository auctionRepository, EventPublisher eventPublisher,
                                  RedisLeaderLock leaderLock) {
        this.auctionRepository = auctionRepository;
        this.eventPublisher = eventPublisher;
        this.leaderLock = leaderLock;
    }

    @Scheduled(fixedRateString = "${bidstream.close-scheduler.scan-interval-ms:5000}")
    public void scanAndEnqueueCloses() {
        String token = leaderLock.tryAcquire(LOCK_KEY, LOCK_TTL);
        if (token == null) {
            return; // another instance holds the lock this tick
        }
        try {
            List<AuctionItem> due = auctionRepository.findDueForClose(Instant.now());
            for (AuctionItem auction : due) {
                CloseCommand command = CloseCommand.of(auction.id(), auction.endTime());
                eventPublisher.publish(TOPIC, auction.id().toString(), command);
                log.info("Enqueued CLOSE for auction={} scheduledEndTime={}", auction.id(), auction.endTime());
            }
        } finally {
            leaderLock.release(LOCK_KEY, token);
        }
    }
}
