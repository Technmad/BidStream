package com.bidstream.adapter.in.kafka;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.application.AuctionCommandProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code auction-processor} on {@code auction.commands} (PDR §10.4). Only BID
 * commands exist on the topic until Phase 4 adds CLOSE; concurrency is set to the partition
 * count so each partition is owned by exactly one thread at a time (PDR §9.1).
 *
 * <p>The offset is acknowledged only after {@link AuctionCommandProcessor#process} returns
 * successfully (its own transaction already committed) - this is the "offset after flush"
 * invariant (PDR §9.6 rule 1), applied per-message until Phase 5 batches it per-partition.
 */
@Component
public class AuctionCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionCommandConsumer.class);

    private final AuctionCommandProcessor processor;

    public AuctionCommandConsumer(AuctionCommandProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = "auction.commands", groupId = "auction-processor", concurrency = "6")
    public void onMessage(ConsumerRecord<String, BidCommand> record, Acknowledgment acknowledgment) {
        BidCommand command = record.value();
        if (command == null) {
            log.error("Poison message on auction.commands at partition={} offset={} - skipping",
                    record.partition(), record.offset());
            acknowledgment.acknowledge();
            return;
        }

        if (!BidCommand.COMMAND_TYPE.equals(command.commandType())) {
            log.warn("Unsupported commandType={} for eventId={} - skipping",
                    command.commandType(), command.eventId());
            acknowledgment.acknowledge();
            return;
        }

        processor.process(command);
        acknowledgment.acknowledge();
    }
}
