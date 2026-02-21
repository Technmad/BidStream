package com.bidstream.application;

import com.bidstream.adapter.in.kafka.AuctionWorkingSet;
import com.bidstream.adapter.messaging.dto.BidAcceptedEvent;
import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.messaging.dto.BidRejectedEvent;
import com.bidstream.adapter.out.persistence.jdbc.BidJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.ProcessedEventJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.ProcessedEventRecord;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Currency;
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
    private static final String BIDS_ACCEPTED_TOPIC = "bids.accepted";
    private static final String BIDS_REJECTED_TOPIC = "bids.rejected";

    private final AuctionWorkingSet workingSet;
    private final AuctionRepository auctionRepository;
    private final ProcessedEventJdbcRepository processedEventRepository;
    private final BidJdbcRepository bidJdbcRepository;
    private final OutboxJdbcRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AuctionCommandProcessor(AuctionWorkingSet workingSet, AuctionRepository auctionRepository,
                                    ProcessedEventJdbcRepository processedEventRepository,
                                    BidJdbcRepository bidJdbcRepository,
                                    OutboxJdbcRepository outboxRepository,
                                    ObjectMapper objectMapper) {
        this.workingSet = workingSet;
        this.auctionRepository = auctionRepository;
        this.processedEventRepository = processedEventRepository;
        this.bidJdbcRepository = bidJdbcRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void process(BidCommand cmd) {
        Optional<ProcessedEventRecord> alreadyProcessed = processedEventRepository.findById(cmd.eventId());
        if (alreadyProcessed.isPresent()) {
            log.info("Replaying stored outcome for eventId={} (already processed)", cmd.eventId());
            return;
        }

        try {
            AuctionItem auction = workingSet.getOrSeed(cmd.auctionId(), () -> seedFromCommittedPostgres(cmd.auctionId()));
            long expectedVersion = auction.version();

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
                return;
            }

            BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
            UUID bidId = UUID.randomUUID();

            bidJdbcRepository.insertIfAbsent(bidId, cmd.auctionId(), cmd.bidderId(), cmd.amount(),
                    cmd.type(), "ACCEPTED", cmd.idempotencyKey(), cmd.occurredAt());

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

            writeOutboxEvent(cmd.auctionId(), BIDS_ACCEPTED_TOPIC, new BidAcceptedEvent(
                    cmd.eventId(), BidCommand.CURRENT_SCHEMA_VERSION, cmd.auctionId(), bidId,
                    cmd.bidderId(), cmd.amount(), accepted.previousWinnerId(),
                    accepted.newEndTime(), cmd.occurredAt(), cmd.correlationId()));
        } catch (RuntimeException ex) {
            workingSet.evict(cmd.auctionId());
            throw ex;
        }
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
