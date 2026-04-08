package com.bidstream.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.out.cache.RedisRateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Exercises the real sliding-window Lua script directly (no HTTP/Kafka round-trip in the loop,
 * which would make the 10s window's density depend on unpredictable network/processing timing).
 * The HTTP-level wiring (which key is chosen, per-user vs per-IP, the 429 response) is simple
 * enough to be covered by reading {@link RateLimitFilter}; what's worth proving against the real
 * Redis is the atomic accept/reject boundary itself. Runs against the local dev stack.
 */
@SpringBootTest
class RateLimitFilterIT {

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Test
    void allowsUpToTheLimitThenRejectsWithinTheWindow() {
        String key = "ratelimit:test:" + UUID.randomUUID();
        int limit = 5;
        Duration window = Duration.ofSeconds(10);

        for (int i = 0; i < limit; i++) {
            assertThat(rateLimiter.tryAcquire(key, limit, window))
                    .as("attempt %d should be within the limit", i)
                    .isTrue();
        }

        assertThat(rateLimiter.tryAcquire(key, limit, window))
                .as("attempt beyond the limit should be rejected")
                .isFalse();
    }

    @Test
    void differentKeysHaveIndependentLimits() {
        String keyA = "ratelimit:test:" + UUID.randomUUID();
        String keyB = "ratelimit:test:" + UUID.randomUUID();
        int limit = 2;
        Duration window = Duration.ofSeconds(10);

        assertThat(rateLimiter.tryAcquire(keyA, limit, window)).isTrue();
        assertThat(rateLimiter.tryAcquire(keyA, limit, window)).isTrue();
        assertThat(rateLimiter.tryAcquire(keyA, limit, window)).isFalse();

        // keyB is untouched by keyA's exhausted limit.
        assertThat(rateLimiter.tryAcquire(keyB, limit, window)).isTrue();
    }
}
