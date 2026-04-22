package com.bidstream.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.domain.model.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against the local dev stack's real Kafka broker (docker/docker-compose.yml).
 */
@SpringBootTest
class SubmitBidCommandUseCaseIT {

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    @Autowired
    private AuctionService auctionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /** Bypasses the register endpoint - this test only needs a row users/auctions can reference. */
    private UUID insertUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash, roles, created_at)
                VALUES (?, ?, ?, 'x', ARRAY['ROLE_USER'], ?)
                """,
                id, prefix + "-" + id, prefix + "-" + id + "@example.com", Timestamp.from(Instant.now()));
        return id;
    }

    @Test
    void publishesBidCommandKeyedByAuctionIdWithCorrectPayload() throws Exception {
        UUID sellerId = insertUser("seller");
        UUID bidderId = insertUser("bidder");
        Currency usd = Currency.getInstance("USD");
        UUID auctionId = auctionService.create(sellerId, null, "Test Lot", "desc",
                Money.of(new BigDecimal("50.00"), usd), null, Money.of(new BigDecimal("5.00"), usd),
                Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now().plus(1, ChronoUnit.HOURS), 30)
                .id();
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

    @Test
    void selfBidIsRejectedAtTheEdgeBeforeEverReachingKafka() {
        // QA-REVIEW.md High finding: self-bid used to only be caught deep in the async
        // processor, wasting a full Kafka round-trip on an outcome the edge already knows for
        // certain. sellerId == bidderId here, so submit() must throw before it ever calls
        // eventPublisher.publish() - proven by the exception itself, since the source has no
        // path to publish() after the self-bid check.
        UUID sellerId = insertUser("seller");
        Currency usd = Currency.getInstance("USD");
        UUID auctionId = auctionService.create(sellerId, null, "Test Lot", "desc",
                Money.of(new BigDecimal("50.00"), usd), null, Money.of(new BigDecimal("5.00"), usd),
                Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now().plus(1, ChronoUnit.HOURS), 30)
                .id();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                submitBidCommandUseCase.submit(auctionId, sellerId, new BigDecimal("60.00"),
                        "USD", UUID.randomUUID().toString())))
                .isInstanceOf(com.bidstream.common.BidRejectedException.class)
                .extracting(ex -> ((com.bidstream.common.BidRejectedException) ex).reason())
                .isEqualTo(com.bidstream.domain.model.BidRejectReason.SELF_BID);
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
