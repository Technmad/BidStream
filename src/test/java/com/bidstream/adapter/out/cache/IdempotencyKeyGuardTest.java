package com.bidstream.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * A pure unit test (no Docker stack) proving the degrade-on-outage path the class's own javadoc
 * promises: "if Redis is unavailable, nothing breaks" (QA-REVIEW.md High finding - this used to
 * be false, since {@code setIfAbsent} had no try/catch at all).
 */
class IdempotencyKeyGuardTest {

    @Test
    void firstUseReturnsTrueWhenRedisIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("Redis connection timed out"));

        IdempotencyKeyGuard guard = new IdempotencyKeyGuard(redisTemplate);

        boolean firstUse = guard.firstUse(UUID.randomUUID(), UUID.randomUUID(), "some-key");

        assertThat(firstUse).isTrue();
    }

    @Test
    void firstUseReturnsTrueOnlyOnceWhenRedisIsHealthy() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true, false);

        IdempotencyKeyGuard guard = new IdempotencyKeyGuard(redisTemplate);
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();

        assertThat(guard.firstUse(auctionId, bidderId, "key")).isTrue();
        assertThat(guard.firstUse(auctionId, bidderId, "key")).isFalse();
    }
}
