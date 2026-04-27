package com.bidstream.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * QA-REVIEW.md production-readiness finding: before this fix, {@code NotifierConsumer} caught its
 * own exceptions and unconditionally acknowledged in a {@code finally} block, so a malformed
 * record on {@code bids.accepted}/{@code bids.rejected}/{@code auctions.events} was silently
 * dropped forever - never retried, never dead-lettered. This proves the same bounded-retry-then-
 * DLQ discipline {@link DlqRoutingIT} already proves for {@code auction.commands} now also
 * applies to the notifier group. Runs against the local dev stack's real Kafka broker.
 */
@SpringBootTest
class NotifierDlqRoutingIT {

    @Autowired
    @Qualifier("outboxKafkaTemplate")
    private KafkaTemplate<String, String> outboxKafkaTemplate;

    private KafkaConsumer<String, String> dlqConsumer;

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-notifier-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqConsumer = new KafkaConsumer<>(props);
        dlqConsumer.subscribe(List.of("bids.accepted.DLQ"));
    }

    @AfterEach
    void tearDown() {
        dlqConsumer.close();
    }

    @Test
    void aMalformedBidsAcceptedRecordEndsUpOnItsDlqInsteadOfBeingSilentlyDropped() throws Exception {
        String marker = "not-valid-json-" + UUID.randomUUID();
        outboxKafkaTemplate.send("bids.accepted", marker, marker).get();

        ConsumerRecord<String, String> dlqRecord = pollForRecord(marker);
        assertThat(dlqRecord.value()).isEqualTo(marker);
    }

    private ConsumerRecord<String, String> pollForRecord(String marker) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (marker.equals(record.value())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Did not find marker=" + marker + " on the DLQ within timeout");
    }
}
