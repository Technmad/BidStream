package com.bidstream.domain.port;

/**
 * Port for publishing domain events onto the messaging fabric. Implemented by a Kafka producer
 * adapter (adapter/out/messaging) wired in Phase 2 (PDR §10).
 */
public interface EventPublisher {

    void publish(String topic, String partitionKey, Object event);
}
