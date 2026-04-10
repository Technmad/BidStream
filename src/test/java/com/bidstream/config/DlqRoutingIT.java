package com.bidstream.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.domain.port.EventPublisher;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A command for an auction that doesn't exist fails every retry, so it must end up on
 * {@code auction.commands.DLQ} rather than looping forever. Runs against the local dev stack's
 * real Kafka broker.
 */
@SpringBootTest
class DlqRoutingIT {

    @Autowired
    private EventPublisher eventPublisher;

    private KafkaConsumer<String, String> dlqConsumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqConsumer = new KafkaConsumer<>(props);
        dlqConsumer.subscribe(List.of("auction.commands.DLQ"));
    }

    @AfterEach
    void tearDown() {
        dlqConsumer.close();
    }

    @Test
    void aCommandForANonExistentAuctionEndsUpOnTheDlq() throws Exception {
        UUID nonExistentAuctionId = UUID.randomUUID();
        BidCommand command = BidCommand.of(nonExistentAuctionId, UUID.randomUUID(),
                new BigDecimal("10.00"), "USD", UUID.randomUUID().toString());
        eventPublisher.publish("auction.commands", nonExistentAuctionId.toString(), command);

        ConsumerRecord<String, String> dlqRecord = pollForRecord(command.eventId());
        assertThat(dlqRecord.value()).contains(command.eventId().toString());
    }

    private ConsumerRecord<String, String> pollForRecord(UUID eventId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(eventId.toString())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Did not find eventId=" + eventId + " on the DLQ within timeout");
    }
}
