package com.bidstream.adapter.out.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * A short-lived Redis lock (PDR §9.5): {@code SET key val NX PX ttl} to acquire, a
 * compare-and-delete Lua script to release (so a holder can never release a lock it no longer
 * owns after its TTL expired and someone else acquired it). Used only for the close-trigger
 * scheduler's leader election - an efficiency measure so five app instances don't all enqueue
 * the same CLOSE, never a correctness dependency (duplicate CLOSE commands are harmless no-ops).
 * Always TTL'd so a crashed holder cannot deadlock the lock.
 */
@Component
public class RedisLeaderLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLeaderLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Returns a non-null token if the lock was acquired, {@code null} otherwise. */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void release(String key, String token) {
        redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
    }
}
