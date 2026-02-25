package com.bidstream.adapter.in.kafka;

import com.bidstream.adapter.in.ws.dto.NotificationMessage;
import com.bidstream.adapter.messaging.dto.BidAcceptedEvent;
import com.bidstream.adapter.messaging.dto.BidRejectedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code notifier} (PDR §10.4): turns bid decisions into targeted per-user
 * pushes. Price fan-out is the ticker (§15.3), never this consumer - these are low-volume,
 * per-event messages. Delivery is best-effort: a user with no active session simply misses it
 * and picks up the current state on their next REST fetch or reconnect (§15.4).
 */
@Component
public class NotifierConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotifierConsumer.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public NotifierConsumer(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "bids.accepted", groupId = "notifier",
            containerFactory = "stringValueKafkaListenerContainerFactory")
    public void onBidAccepted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            BidAcceptedEvent event = objectMapper.readValue(record.value(), BidAcceptedEvent.class);
            if (event.previousWinnerId() != null) {
                messagingTemplate.convertAndSendToUser(event.previousWinnerId().toString(),
                        "/queue/notifications",
                        NotificationMessage.Outbid.of(event.auctionId(), event.amount()));
            }
            messagingTemplate.convertAndSendToUser(event.bidderId().toString(), "/queue/notifications",
                    NotificationMessage.BidResult.of(event.correlationId(), "ACCEPTED"));
        } catch (Exception e) {
            log.error("Failed to process bids.accepted record at offset={}", record.offset(), e);
        } finally {
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = "bids.rejected", groupId = "notifier",
            containerFactory = "stringValueKafkaListenerContainerFactory")
    public void onBidRejected(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            BidRejectedEvent event = objectMapper.readValue(record.value(), BidRejectedEvent.class);
            messagingTemplate.convertAndSendToUser(event.bidderId().toString(), "/queue/notifications",
                    NotificationMessage.BidResult.of(event.correlationId(), "REJECTED:" + event.reason()));
        } catch (Exception e) {
            log.error("Failed to process bids.rejected record at offset={}", record.offset(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
