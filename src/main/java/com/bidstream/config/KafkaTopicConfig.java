package com.bidstream.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topic provisioning (PDR §10.1). Partition counts here are dev-sized; production should
 * over-provision {@code auction.commands} well above expected concurrent-hot-auction count,
 * since increasing partitions later reshuffles the key→partition mapping.
 */
@Configuration
public class KafkaTopicConfig {

    /** Unified inbound command log: carries both BID and CLOSE, keyed by auctionId. */
    @Bean
    public NewTopic auctionCommandsTopic() {
        return TopicBuilder.name("auction.commands")
                .partitions(6)
                .replicas(1)
                .config("retention.ms", String.valueOf(java.time.Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    public NewTopic bidsAcceptedTopic() {
        return TopicBuilder.name("bids.accepted")
                .partitions(6)
                .replicas(1)
                .config("retention.ms", String.valueOf(java.time.Duration.ofDays(30).toMillis()))
                .build();
    }

    @Bean
    public NewTopic bidsRejectedTopic() {
        return TopicBuilder.name("bids.rejected")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(java.time.Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    public NewTopic auctionsEventsTopic() {
        return TopicBuilder.name("auctions.events")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(java.time.Duration.ofDays(30).toMillis()))
                .build();
    }

    @Bean
    public NewTopic notificationsTopic() {
        return TopicBuilder.name("notifications")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(java.time.Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    public NewTopic auctionCommandsDlqTopic() {
        return TopicBuilder.name("auction.commands.DLQ")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(java.time.Duration.ofDays(14).toMillis()))
                .build();
    }
}
