package com.bidstream.application;

import com.bidstream.adapter.in.kafka.AuctionWorkingSet;
import com.bidstream.adapter.messaging.dto.AuctionEndedEvent;
import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.messaging.dto.BidRejectedEvent;
import com.bidstream.adapter.messaging.dto.CloseCommand;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.ProcessedEventJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.ProcessedEventRecord;
import com.bidstream.adapter.out.persistence.jdbc.SettlementJdbcRepository;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AutoBid;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.BidType;
import com.bidstream.domain.model.CloseOutcome;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import com.bidstream.domain.port.AutoBidRepository;
import com.bidstream.domain.service.AutoBidResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single-writer auction-processor core (PDR §9.1, §9.6). Every {@link BidCommand} for a
 * given auction is handled here, one at a time, by whichever consumer thread owns that
 * auction's Kafka partition — so this class never needs its own locking for a single auction.
 *
 * <p>This is the correctness-preserving <em>per-message</em> version of §9.6: every durable
 * write for one command happens in one transaction, and the caller (the Kafka listener) commits
 * the offset only after this method returns successfully. The write-behind <em>batching</em>
 * optimization (buffering several commands' writes into one flush) is layered on top of this in
 * Phase 5 without changing the correctness invariants established here.
 */
@Service
public class AuctionCommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(AuctionCommandProcessor.class);
    private static final String BIDS_REJECTED_TOPIC = "bids.rejected";
    private static final String AUCTIONS_EVENTS_TOPIC = "auctions.events";

    private final AuctionWorkingSet workingSet;
    private final AuctionRepository auctionRepository;
    private final ProcessedEventJdbcRepository processedEventRepository;
    private final OutboxJdbcRepository outboxRepository;
    private final AutoBidRepository autoBidRepository;
    private final AcceptedBidPersister acceptedBidPersister;
    private final SettlementJdbcRepository settlementRepository;
    private final ObjectMapper objectMapper;
    private final Counter bidsAcceptedCounter;
    private final Counter replaysCounter;
    private final Timer decisionLatencyTimer;
    private final MeterRegistry meterRegistry;

    public AuctionCommandProcessor(AuctionWorkingSet workingSet, AuctionRepository auctionRepository,
                                    ProcessedEventJdbcRepository processedEventRepository,
                                    OutboxJdbcRepository outboxRepository,
                                    AutoBidRepository autoBidRepository,
                                    AcceptedBidPersister acceptedBidPersister,
                                    SettlementJdbcRepository settlementRepository,
                                    ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.workingSet = workingSet;
        this.auctionRepository = auctionRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxRepository = outboxRepository;
        this.autoBidRepository = autoBidRepository;
        this.acceptedBidPersister = acceptedBidPersister;
        this.settlementRepository = settlementRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        // PDR §18: bid accept/reject rate, replay/dedup hit rate, decision latency.
        this.bidsAcceptedCounter = Counter.builder("bidstream.bids").tag("outcome", "accepted")
                .description("Bids decided by the auction-processor").register(meterRegistry);
        this.replaysCounter = Counter.builder("bidstream.processor.replays")
                .description("Commands short-circuited by the processed_events dedup gate")
                .register(meterRegistry);
        this.decisionLatencyTimer = Timer.builder("bidstream.bid.decision.latency")
                .description("Time from a command's occurredAt to its durable decision (PDR §4)")
                .register(meterRegistry);
    }

    @Transactional
    public BidDecisionWaiter.Decision process(BidCommand cmd) {
        Optional<ProcessedEventRecord> alreadyProcessed = processedEventRepository.findById(cmd.eventId());
        if (alreadyProcessed.isPresent()) {
            log.info("Replaying stored outcome for eventId={} (already processed)", cmd.eventId());
            replaysCounter.increment();
            return null;
        }

        try {
            AuctionItem auction = workingSet.getOrSeed(cmd.auctionId(), () -> seedFromCommittedPostgres(cmd.auctionId()));
            long expectedVersion = auction.version();
            Money priceBeforeThisCommand = auction.currentPrice();

            Money amount = Money.of(cmd.amount(), Currency.getInstance(cmd.currency()));
            BidOutcome outcome = auction.placeBid(cmd.bidderId(), amount, cmd.occurredAt());
            decisionLatencyTimer.record(Duration.between(cmd.occurredAt(), Instant.now()));

            if (outcome instanceof BidOutcome.Rejected rejected) {
                meterRegistry.counter("bidstream.bids", "outcome", "rejected", "reason",
                        rejected.reason().name()).increment();
                processedEventRepository.insertIfAbsent(new ProcessedEventRecord(
                        cmd.eventId(), cmd.auctionId(), "REJECTED", rejected.reason().name(),
                        null, null, cmd.occurredAt()));
                writeOutboxEvent(cmd.auctionId(), BIDS_REJECTED_TOPIC, new BidRejectedEvent(
                        cmd.eventId(), BidCommand.CURRENT_SCHEMA_VERSION, cmd.auctionId(),
                        cmd.bidderId(), rejected.reason().name(), auction.currentPrice().amount(),
                        auction.minIncrement().amount(), cmd.occurredAt(), cmd.correlationId()));
                return BidDecisionWaiter.Decision.rejected(rejected, auction.currentPrice().amount(),
                        auction.minIncrement().amount());
            }

            BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
            UUID bidId = UUID.randomUUID();
            acceptedBidPersister.persist(cmd.auctionId(), cmd.bidderId(), cmd.amount(), cmd.type(),
                    cmd.idempotencyKey(), cmd.occurredAt(), bidId, accepted, cmd.eventId(), cmd.correlationId());

            // Auto-bid ladder (PDR §12): if another bidder has a standing proxy max higher than
            // this manual bid, the system immediately counter-bids on their behalf - resolved
            // against the price *before* this command, exactly like PDR §12.2's worked example.
            // The manual bidder's own HTTP response always reflects THEIR bid's outcome (it was
            // genuinely accepted); an immediate auto-counter-bid reaches them separately as an
            // OUTBID push over WebSocket, exactly like a human outbidding them a moment later.
            maybeResolveAutoBid(auction, cmd.auctionId(), cmd.bidderId(), priceBeforeThisCommand,
                    amount, cmd.occurredAt());

            boolean persisted = auctionRepository.saveWithOptimisticLock(auction, expectedVersion);
            if (!persisted) {
                // Should not happen under true single-writer-per-partition; treat as a signal
                // to reseed and let Kafka redeliver rather than silently diverging from Postgres.
                workingSet.evict(cmd.auctionId());
                throw new IllegalStateException(
                        "Optimistic-lock conflict on auction " + cmd.auctionId()
                                + " despite single-writer partitioning - reseeding for retry");
            }
            workingSet.put(cmd.auctionId(), auction);

            processedEventRepository.insertIfAbsent(new ProcessedEventRecord(
                    cmd.eventId(), cmd.auctionId(), "ACCEPTED", null,
                    accepted.newPrice().amount(), accepted.newWinnerId(), cmd.occurredAt()));
            bidsAcceptedCounter.increment();

            return BidDecisionWaiter.Decision.accepted(accepted, bidId);
        } catch (RuntimeException ex) {
            workingSet.evict(cmd.auctionId());
            throw ex;
        }
    }

    /**
     * Closes an auction (PDR §11.3). Ordered on the same partition as every bid for this
     * auction, so "did this bid beat the close?" is answered purely by log position - a bid
     * consumed before this CLOSE has already been applied; one consumed after is rejected
     * AUCTION_ENDED by {@link AuctionItem#placeBid}. A stale or duplicate CLOSE (already
     * terminal, or superseded by an anti-snipe extension) is a harmless no-op.
     */
    @Transactional
    public void processClose(CloseCommand cmd) {
        if (processedEventRepository.findById(cmd.eventId()).isPresent()) {
            log.info("Replaying stored outcome for CLOSE eventId={} (already processed)", cmd.eventId());
            replaysCounter.increment();
            return;
        }

        try {
            AuctionItem auction = workingSet.getOrSeed(cmd.auctionId(), () -> seedFromCommittedPostgres(cmd.auctionId()));
            long expectedVersion = auction.version();

            CloseOutcome outcome = auction.close(cmd.scheduledEndTime());
            if (outcome instanceof CloseOutcome.Ignored ignored) {
                log.info("Ignoring stale/duplicate CLOSE for auction={}: {}", cmd.auctionId(), ignored.reason());
                return;
            }

            boolean persisted = auctionRepository.saveWithOptimisticLock(auction, expectedVersion);
            if (!persisted) {
                workingSet.evict(cmd.auctionId());
                throw new IllegalStateException(
                        "Optimistic-lock conflict closing auction " + cmd.auctionId() + " - reseeding for retry");
            }
            workingSet.put(cmd.auctionId(), auction);

            UUID winnerId = outcome instanceof CloseOutcome.Sold sold ? sold.winnerId() : null;
            var finalPrice = outcome instanceof CloseOutcome.Sold sold ? sold.finalPrice().amount() : null;
            String outcomeName = outcome instanceof CloseOutcome.Sold ? "SOLD" : "UNSOLD";

            settlementRepository.insertIfAbsent(cmd.auctionId(), winnerId, finalPrice, outcomeName);

            processedEventRepository.insertIfAbsent(new ProcessedEventRecord(
                    cmd.eventId(), cmd.auctionId(), outcomeName, null, finalPrice, winnerId, cmd.occurredAt()));

            writeOutboxEvent(cmd.auctionId(), AUCTIONS_EVENTS_TOPIC, new AuctionEndedEvent(
                    cmd.eventId(), BidCommand.CURRENT_SCHEMA_VERSION, cmd.auctionId(), outcomeName,
                    winnerId, finalPrice, cmd.occurredAt(), cmd.correlationId()));
        } catch (RuntimeException ex) {
            workingSet.evict(cmd.auctionId());
            throw ex;
        }
    }

    /**
     * If a standing auto-bid competes with the manual bid just accepted, the resolved ladder
     * outcome (PDR §12.1) supersedes {@code placeBid}'s raw acceptance - even when the manual
     * bidder still wins, they only pay enough to beat the runner-up, exactly like a manual bid
     * competing against a proxy leader (the manual amount is itself treated as an implicit max,
     * per §12.2's worked example). When the leader instead reclaims, its counter-bid is
     * persisted like a normal accepted bid (its own {@code bids} row + outbox event) with
     * {@code previousWinnerId} set to the manual bidder, so the existing notifier wiring
     * naturally sends them an OUTBID push.
     */
    private void maybeResolveAutoBid(AuctionItem auction, UUID auctionId, UUID manualBidderId,
                                      Money priceBeforeThisCommand, Money manualAmount, Instant occurredAt) {
        List<AutoBid> active = autoBidRepository.findActiveByAuctionId(auctionId);
        AutoBid leaderAutoBid = active.stream()
                .filter(ab -> !ab.bidderId().equals(manualBidderId))
                .max((a, b) -> {
                    int cmp = a.maxAmount().compareTo(b.maxAmount());
                    return cmp != 0 ? cmp : b.createdAt().compareTo(a.createdAt());
                })
                .orElse(null);
        if (leaderAutoBid == null) {
            return;
        }

        // leader is non-null here (checked above), so resolve() always returns a resolution -
        // the empty case only applies to the no-leader floor check.
        AutoBidResolver.Resolution resolution = AutoBidResolver.resolve(
                priceBeforeThisCommand, auction.minIncrement(),
                new AutoBidResolver.Leader(leaderAutoBid.bidderId(), leaderAutoBid.maxAmount(), leaderAutoBid.createdAt()),
                new AutoBidResolver.Challenger(manualBidderId, manualAmount, occurredAt))
                .orElseThrow();

        // Always apply the resolved outcome - it supersedes placeBid's raw acceptance whenever a
        // competing auto-bid exists, whether the manual bidder still wins (at a capped price) or
        // the standing leader immediately reclaims.
        BidOutcome.Accepted resolvedOutcome = auction.applyResolvedBid(
                resolution.winnerId(), resolution.price(), occurredAt);

        if (resolution.winnerId().equals(manualBidderId)) {
            // The manual bidder's own bids-row (already persisted at their full amount) covers
            // this - no separate counter-bid to record.
            return;
        }

        UUID autoBidRowId = UUID.randomUUID();
        String syntheticIdempotencyKey = "auto:" + UUID.randomUUID();
        acceptedBidPersister.persist(auctionId, leaderAutoBid.bidderId(), resolution.price().amount(),
                BidType.AUTO.name(), syntheticIdempotencyKey, occurredAt, autoBidRowId, resolvedOutcome,
                UUID.randomUUID(), UUID.randomUUID());
    }

    private AuctionItem seedFromCommittedPostgres(UUID auctionId) {
        return auctionRepository.findById(auctionId)
                .orElseThrow(() -> new NotFoundException("Auction not found: " + auctionId));
    }

    private void writeOutboxEvent(UUID auctionId, String topic, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            outboxRepository.insert(auctionId, topic, auctionId.toString(), json);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event for " + topic, e);
        }
    }
}
