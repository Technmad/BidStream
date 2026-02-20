package com.bidstream.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Runs against the local dev stack's real Kafka broker (docker/docker-compose.yml).
 */
@SpringBootTest
class SubmitBidCommandUseCaseIT {

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    private KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("auction.commands"));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void publishesBidCommandKeyedByAuctionIdWithCorrectPayload() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        BidCommand submitted = submitBidCommandUseCase.submit(
                auctionId, bidderId, new BigDecimal("75.00"), "USD", idempotencyKey);

        ConsumerRecord<String, String> record = pollForRecord(submitted.eventId());

        assertThat(record.key()).isEqualTo(auctionId.toString());
        Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
        assertThat(payload.get("commandType")).isEqualTo("BID");
        assertThat(payload.get("auctionId")).isEqualTo(auctionId.toString());
        assertThat(payload.get("bidderId")).isEqualTo(bidderId.toString());
        assertThat(payload.get("idempotencyKey")).isEqualTo(idempotencyKey);
        assertThat(((Number) payload.get("amount")).doubleValue()).isEqualTo(75.00);
    }

    private ConsumerRecord<String, String> pollForRecord(UUID eventId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
                if (eventId.toString().equals(payload.get("eventId"))) {
                    return record;
                }
            }
        }
        throw new AssertionError("Did not find published BidCommand with eventId=" + eventId
                + " within timeout");
    }
}
