package com.bidstream.adapter.out.cache;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fast-path idempotency pre-filter (PDR §13): {@code SET idem:{auctionId}:{key} NX PX 24h}
 * sheds duplicate submissions before they ever reach Kafka. This is a load optimization, not
 * the correctness mechanism - if the key expires or Redis is unavailable, nothing breaks,
 * because the never-expiring DB unique constraint on {@code bids} remains the authoritative
 * guard (checked separately, durably, regardless of what this cache says).
 */
@Component
public class IdempotencyKeyGuard {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public IdempotencyKeyGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Returns {@code true} if this is the first time this key has been seen (safe to proceed). */
    public boolean firstUse(UUID auctionId, UUID bidderId, String idempotencyKey) {
        String key = "idem:" + auctionId + ":" + bidderId + ":" + idempotencyKey;
        Boolean firstUse = redisTemplate.opsForValue().setIfAbsent(key, "1", TTL);
        return Boolean.TRUE.equals(firstUse);
    }
}
