package com.bidstream.adapter.out.cache;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiter backed by a Redis sorted set (PDR §13, §17). Atomic via a Lua
 * script: trims entries older than the window, adds the current attempt, and checks the
 * resulting cardinality against the limit - all in one round trip, so concurrent requests from
 * the same key can't race past the limit.
 */
@Component
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, now .. '-' .. math.random())
            redis.call('PEXPIRE', key, windowMs)
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Returns {@code true} if this attempt is within the limit and was recorded. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Long result = redisTemplate.execute(SCRIPT, List.of(key),
                String.valueOf(System.currentTimeMillis()), String.valueOf(window.toMillis()),
                String.valueOf(limit));
        return result != null && result == 1L;
    }
}
