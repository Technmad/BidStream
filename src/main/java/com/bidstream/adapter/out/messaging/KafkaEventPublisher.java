package com.bidstream.adapter.out.messaging;

import com.bidstream.domain.port.EventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka-backed implementation of the {@link EventPublisher} port (PDR §10). The producer is
 * configured for {@code acks=all} + idempotence (PDR §10.5) at the {@code KafkaTemplate} level
 * via {@code application.yml}, so every publish here is durable and non-duplicating on retry.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, String partitionKey, Object event) {
        kafkaTemplate.send(topic, partitionKey, event);
    }
}
