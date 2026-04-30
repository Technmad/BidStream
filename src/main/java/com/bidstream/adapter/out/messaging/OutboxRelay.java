package com.bidstream.adapter.out.messaging;

import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository.OutboxRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls {@code idx_outbox_unpublished} and relays rows to Kafka at-least-once (PDR §10.3). Every
 * downstream consumer (notifier, read-model, analytics) is expected to dedupe by {@code eventId}
 * in the payload, exactly like the processor's own {@code processed_events} gate.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxJdbcRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(OutboxJdbcRepository outboxRepository,
                        KafkaTemplate<String, String> kafkaTemplate,
                        PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * No class/method-level {@code @Transactional} here on purpose: the batch used to be fetched
     * and every row marked published inside one long transaction that stayed open across up to
     * BATCH_SIZE blocking {@code kafkaTemplate...get()} network round-trips, pinning a single
     * Postgres connection for the whole poll. Instead the fetch below is its own short
     * transaction (just long enough for the {@code FOR UPDATE SKIP LOCKED} read), each row's
     * Kafka send happens with no transaction open at all, and only the row's own
     * {@link #markPublished} commit is transactional - so a connection is never held across a
     * Kafka round-trip.
     */
    @Scheduled(fixedDelayString = "${bidstream.outbox.relay-interval-ms:500}")
    public void relay() {
        List<OutboxRow> rows = transactionTemplate.execute(status -> outboxRepository.findUnpublished(BATCH_SIZE));
        for (OutboxRow row : rows) {
            try {
                // Synchronous send outside of any transaction: if Kafka is unreachable, the
                // row stays unpublished (nothing marked) and is retried on the next tick.
                kafkaTemplate.send(row.topic(), row.partitionKey(), row.payload()).get();
                markPublished(row.id());
            } catch (Exception e) {
                log.error("Failed to relay outbox row id={} topic={} - will retry next tick",
                        row.id(), row.topic(), e);
            }
        }
    }

    /** Commits this single row's publish on its own, immediately after its Kafka send succeeds. */
    private void markPublished(long id) {
        transactionTemplate.executeWithoutResult(status -> outboxRepository.markPublished(id));
    }
}
