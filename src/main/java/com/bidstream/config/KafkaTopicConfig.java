package com.bidstream.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic provisioning (PDR §10.1). Partition counts here are dev-sized; production should
 * over-provision {@code auction.commands} well above expected concurrent-hot-auction count,
 * since increasing partitions later reshuffles the key→partition mapping.
 *
 * <p>QA-REVIEW.md production-readiness finding: every topic was hardcoded to a single replica, so
 * {@code min.insync.replicas ≥ 2} - which PDR §4 requires for zero committed-bid loss - is
 * structurally impossible regardless of the producer's own {@code acks=all} config
 * (application.yml). {@code bidstream.kafka.topic-replication-factor} now controls it; the
 * default of 1 keeps the local single-broker dev stack working exactly as before, but production
 * must set it to at least 3 against a real multi-broker cluster. {@code min.insync.replicas} is
 * derived from it rather than hardcoded, so the two can never drift apart into an invalid
 * combination.
 */
@Configuration
public class KafkaTopicConfig {

    private final int replicationFactor;
    private final int minInsyncReplicas;

    public KafkaTopicConfig(@Value("${bidstream.kafka.topic-replication-factor:1}") int replicationFactor) {
        this.replicationFactor = replicationFactor;
        // Never ask for more in-sync replicas than the topic even has; on a single-broker dev
        // stack (replicationFactor=1) this is 1, matching today's behavior exactly.
        this.minInsyncReplicas = Math.min(2, replicationFactor);
    }

    private TopicBuilder topic(String name, int partitions, java.time.Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("min.insync.replicas", String.valueOf(minInsyncReplicas))
                .config("retention.ms", String.valueOf(retention.toMillis()));
    }

    /** Unified inbound command log: carries both BID and CLOSE, keyed by auctionId. */
    @Bean
    public NewTopic auctionCommandsTopic() {
        return topic("auction.commands", 6, java.time.Duration.ofDays(7)).build();
    }

    @Bean
    public NewTopic bidsAcceptedTopic() {
        return topic("bids.accepted", 6, java.time.Duration.ofDays(30)).build();
    }

    @Bean
    public NewTopic bidsRejectedTopic() {
        return topic("bids.rejected", 3, java.time.Duration.ofDays(7)).build();
    }

    @Bean
    public NewTopic auctionsEventsTopic() {
        return topic("auctions.events", 3, java.time.Duration.ofDays(30)).build();
    }

    @Bean
    public NewTopic notificationsTopic() {
        return topic("notifications", 3, java.time.Duration.ofDays(7)).build();
    }

    @Bean
    public NewTopic auctionCommandsDlqTopic() {
        return topic("auction.commands.DLQ", 3, java.time.Duration.ofDays(14)).build();
    }

    /**
     * QA-REVIEW.md production-readiness finding: the {@code notifier} group's listeners had no
     * DLQ at all, unlike {@code auction.commands} - a malformed record on any of these three
     * topics was silently dropped forever. {@code KafkaConfig}'s
     * {@code stringValueKafkaListenerContainerFactory} now routes a permanently-failing record on
     * {@code <topic>} to {@code <topic>.DLQ}, so each source topic needs its DLQ counterpart.
     */
    @Bean
    public NewTopic bidsAcceptedDlqTopic() {
        return topic("bids.accepted.DLQ", 3, java.time.Duration.ofDays(14)).build();
    }

    @Bean
    public NewTopic bidsRejectedDlqTopic() {
        return topic("bids.rejected.DLQ", 3, java.time.Duration.ofDays(14)).build();
    }

    @Bean
    public NewTopic auctionsEventsDlqTopic() {
        return topic("auctions.events.DLQ", 3, java.time.Duration.ofDays(14)).build();
    }
}
