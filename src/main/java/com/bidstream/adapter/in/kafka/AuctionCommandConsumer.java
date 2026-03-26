package com.bidstream.adapter.in.kafka;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.messaging.dto.CloseCommand;
import com.bidstream.application.AuctionCommandProcessor;
import com.bidstream.application.BidDecisionWaiter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code auction-processor} on {@code auction.commands} (PDR §10.4). Carries
 * both BID and CLOSE commands (PDR §11.3), so the value type here is {@code Object} - Spring's
 * JsonDeserializer resolves each record to its original producer class via the type header, and
 * this dispatches on the actual runtime type. Concurrency is set to the partition count so each
 * partition is owned by exactly one thread at a time (PDR §9.1).
 *
 * <p>The offset is acknowledged only after the processor's transaction has committed - this is
 * the "offset after flush" invariant (PDR §9.6 rule 1), applied per-message until Phase 5
 * batches it per-partition.
 */
@Component
public class AuctionCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionCommandConsumer.class);

    private final AuctionCommandProcessor processor;
    private final BidDecisionWaiter decisionWaiter;

    public AuctionCommandConsumer(AuctionCommandProcessor processor, BidDecisionWaiter decisionWaiter) {
        this.processor = processor;
        this.decisionWaiter = decisionWaiter;
    }

    @KafkaListener(topics = "auction.commands", groupId = "auction-processor", concurrency = "6")
    public void onMessage(ConsumerRecord<String, Object> record, Acknowledgment acknowledgment) {
        Object command = record.value();
        if (command == null) {
            log.error("Poison message on auction.commands at partition={} offset={} - skipping",
                    record.partition(), record.offset());
            acknowledgment.acknowledge();
            return;
        }

        if (command instanceof BidCommand bidCommand) {
            BidDecisionWaiter.Decision decision = processor.process(bidCommand);
            // Completing after process() returns means the transaction has already committed
            // (Spring's @Transactional proxy commits before returning), so a caller woken up
            // here will see consistent state on its next read.
            if (decision != null) {
                decisionWaiter.complete(bidCommand.eventId(), decision);
            }
        } else if (command instanceof CloseCommand closeCommand) {
            processor.processClose(closeCommand);
        } else {
            log.warn("Unsupported command type {} at offset={} - skipping",
                    command.getClass(), record.offset());
        }
        acknowledgment.acknowledge();
    }
}
