package com.bidstream.adapter.in.kafka;

import com.bidstream.adapter.in.ws.dto.AuctionEndedMessage;
import com.bidstream.adapter.in.ws.dto.AuctionExtendedMessage;
import com.bidstream.adapter.in.ws.dto.NotificationMessage;
import com.bidstream.adapter.messaging.dto.AuctionEndedEvent;
import com.bidstream.adapter.messaging.dto.BidAcceptedEvent;
import com.bidstream.adapter.messaging.dto.BidRejectedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code notifier} (PDR §10.4): turns bid/auction-lifecycle decisions into
 * client pushes. Most of these are targeted, low-volume per-user messages
 * (accept/reject/outbid) - the ongoing price fan-out is the ticker's job (§15.3), never this
 * consumer's. The two one-time lifecycle events every watcher needs (an anti-snipe extension, an
 * auction closing) are the exception: those are broadcast to {@code /topic/auctions/{id}}
 * exactly like the ticker's own messages, since every subscriber needs them, not just the
 * bidder involved. Delivery is best-effort: a user with no active session simply misses it and
 * picks up the current state on their next REST fetch or reconnect (§15.4).
 */
@Component
public class NotifierConsumer {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public NotifierConsumer(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "bids.accepted", groupId = "notifier",
            containerFactory = "stringValueKafkaListenerContainerFactory")
    public void onBidAccepted(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        // Deliberately no try/catch here (see KafkaConfig's stringValueKafkaListenerContainerFactory
        // javadoc): an exception must propagate to the container's error handler so a malformed
        // record gets bounded-retried then dead-lettered, instead of being silently dropped by a
        // catch-and-unconditionally-ack here.
        BidAcceptedEvent event = objectMapper.readValue(record.value(), BidAcceptedEvent.class);
        if (event.previousWinnerId() != null) {
            messagingTemplate.convertAndSendToUser(event.previousWinnerId().toString(),
                    "/queue/notifications",
                    NotificationMessage.Outbid.of(event.auctionId(), event.amount()));
        }
        messagingTemplate.convertAndSendToUser(event.bidderId().toString(), "/queue/notifications",
                NotificationMessage.BidResult.of(event.correlationId(), "ACCEPTED"));

        if (event.extended()) {
            messagingTemplate.convertAndSend("/topic/auctions/" + event.auctionId(),
                    AuctionExtendedMessage.of(event.auctionId(), event.newEndTime()));
        }
        ack.acknowledge();
    }

    @KafkaListener(topics = "bids.rejected", groupId = "notifier",
            containerFactory = "stringValueKafkaListenerContainerFactory")
    public void onBidRejected(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        BidRejectedEvent event = objectMapper.readValue(record.value(), BidRejectedEvent.class);
        messagingTemplate.convertAndSendToUser(event.bidderId().toString(), "/queue/notifications",
                NotificationMessage.BidResult.of(event.correlationId(), "REJECTED:" + event.reason()));
        ack.acknowledge();
    }

    /**
     * PDR §15.3's "guaranteed final push": the outbox row for a close outcome is already
     * written inside the same transaction as the settlement (PDR §11.3,
     * {@code AuctionCommandProcessor.processClose}) - this just broadcasts it once it reaches
     * Kafka, so every watcher of {@code /topic/auctions/{id}} learns the auction ended without
     * waiting for the ticker's opportunistic next tick.
     */
    @KafkaListener(topics = "auctions.events", groupId = "notifier",
            containerFactory = "stringValueKafkaListenerContainerFactory")
    public void onAuctionEnded(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        AuctionEndedEvent event = objectMapper.readValue(record.value(), AuctionEndedEvent.class);
        messagingTemplate.convertAndSend("/topic/auctions/" + event.auctionId(),
                AuctionEndedMessage.of(event.auctionId(), event.outcome(), event.winnerId(), event.finalPrice()));
        ack.acknowledge();
    }
}
