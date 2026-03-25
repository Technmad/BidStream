package com.bidstream.application;

import com.bidstream.adapter.in.kafka.AuctionWorkingSet;
import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.messaging.dto.BidRejectedEvent;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.ProcessedEventJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.ProcessedEventRecord;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AutoBid;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.BidType;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import com.bidstream.domain.port.AutoBidRepository;
import com.bidstream.domain.service.AutoBidResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final AuctionWorkingSet workingSet;
    private final AuctionRepository auctionRepository;
    private final ProcessedEventJdbcRepository processedEventRepository;
    private final OutboxJdbcRepository outboxRepository;
    private final AutoBidRepository autoBidRepository;
    private final AcceptedBidPersister acceptedBidPersister;
    private final ObjectMapper objectMapper;

    public AuctionCommandProcessor(AuctionWorkingSet workingSet, AuctionRepository auctionRepository,
                                    ProcessedEventJdbcRepository processedEventRepository,
                                    OutboxJdbcRepository outboxRepository,
                                    AutoBidRepository autoBidRepository,
                                    AcceptedBidPersister acceptedBidPersister,
                                    ObjectMapper objectMapper) {
        this.workingSet = workingSet;
        this.auctionRepository = auctionRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxRepository = outboxRepository;
        this.autoBidRepository = autoBidRepository;
        this.acceptedBidPersister = acceptedBidPersister;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BidDecisionWaiter.Decision process(BidCommand cmd) {
        Optional<ProcessedEventRecord> alreadyProcessed = processedEventRepository.findById(cmd.eventId());
        if (alreadyProcessed.isPresent()) {
            log.info("Replaying stored outcome for eventId={} (already processed)", cmd.eventId());
            return null;
        }

        try {
            AuctionItem auction = workingSet.getOrSeed(cmd.auctionId(), () -> seedFromCommittedPostgres(cmd.auctionId()));
            long expectedVersion = auction.version();
            Money priceBeforeThisCommand = auction.currentPrice();

            Money amount = Money.of(cmd.amount(), Currency.getInstance(cmd.currency()));
            BidOutcome outcome = auction.placeBid(cmd.bidderId(), amount, cmd.occurredAt());

            if (outcome instanceof BidOutcome.Rejected rejected) {
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

            return BidDecisionWaiter.Decision.accepted(accepted, bidId);
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

        AutoBidResolver.Resolution resolution = AutoBidResolver.resolve(
                priceBeforeThisCommand, auction.minIncrement(),
                new AutoBidResolver.Leader(leaderAutoBid.bidderId(), leaderAutoBid.maxAmount(), leaderAutoBid.createdAt()),
                new AutoBidResolver.Challenger(manualBidderId, manualAmount, occurredAt));

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
