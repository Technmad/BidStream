package com.bidstream.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Bounds retry on a permanently-failing record (e.g. an auction that no longer exists) so it
 * cannot retry forever and block its partition. This is a stopgap — full dead-letter routing
 * (PDR §10.5) lands in the Phase 5 hardening branch; for now a poison message is logged and
 * skipped after a few attempts rather than wedging the consumer indefinitely.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConsumerFactory<String, Object> auctionCommandsConsumerFactory(KafkaProperties kafkaProperties) {
        var props = kafkaProperties.buildConsumerProperties(null);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(new JsonDeserializer<>()));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> auctionCommandsConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(auctionCommandsConsumerFactory);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);

        // Retry a failing record 3 times (1s apart), then skip it and move on - see class javadoc.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    org.slf4j.LoggerFactory.getLogger(KafkaConfig.class).error(
                            "Giving up on record at partition={} offset={} after retries exhausted: {}",
                            record.partition(), record.offset(), ex.getMessage());
                },
                new FixedBackOff(1000L, 3));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /**
     * Consumer factory for topics fed by the {@link com.bidstream.adapter.out.messaging.OutboxRelay}
     * (bids.accepted/bids.rejected) - the relay publishes raw JSON text with no type headers, so
     * these listeners deserialize to {@code String} and parse manually rather than relying on
     * JsonDeserializer's type-header inference (which the auction.commands factory uses).
     */
    @Bean
    public ConsumerFactory<String, String> stringValueConsumerFactory(KafkaProperties kafkaProperties) {
        var props = kafkaProperties.buildConsumerProperties(null);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> stringValueKafkaListenerContainerFactory(
            ConsumerFactory<String, String> stringValueConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stringValueConsumerFactory);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        return factory;
    }

    /**
     * A raw-String producer for the {@link com.bidstream.adapter.out.messaging.OutboxRelay}:
     * outbox payloads are already-serialized JSON text, so this must send them byte-for-byte
     * rather than re-encoding them through the JSON-object {@code KafkaTemplate} used elsewhere.
     */
    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Defining any {@code KafkaTemplate} bean above disables Spring Boot's own autoconfigured
     * one ({@code @ConditionalOnMissingBean(KafkaTemplate.class)} matches by raw type, ignoring
     * generics) - so the default JSON-object template used by {@code KafkaEventPublisher} and
     * the auction-processor's own commands must be re-declared explicitly here.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        ProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(producerFactory);
    }
}
