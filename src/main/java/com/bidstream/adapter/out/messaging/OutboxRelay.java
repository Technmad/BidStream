package com.bidstream.adapter.out.messaging;

import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository;
import com.bidstream.adapter.out.persistence.jdbc.OutboxJdbcRepository.OutboxRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    public OutboxRelay(OutboxJdbcRepository outboxRepository,
                        KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${bidstream.outbox.relay-interval-ms:500}")
    @Transactional
    public void relay() {
        List<OutboxRow> rows = outboxRepository.findUnpublished(BATCH_SIZE);
        for (OutboxRow row : rows) {
            try {
                // Synchronous send within the poll transaction: if Kafka is unreachable, the
                // row stays unpublished (nothing marked) and is retried on the next tick.
                kafkaTemplate.send(row.topic(), row.partitionKey(), row.payload()).get();
                outboxRepository.markPublished(row.id());
            } catch (Exception e) {
                log.error("Failed to relay outbox row id={} topic={} - will retry next tick",
                        row.id(), row.topic(), e);
            }
        }
    }
}
