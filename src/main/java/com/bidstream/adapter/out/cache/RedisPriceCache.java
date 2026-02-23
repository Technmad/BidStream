package com.bidstream.adapter.out.cache;

import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.PriceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed hot-price projection (PDR §13). Entirely rebuildable from Postgres - Redis holds
 * no unique source-of-truth data. The writer updates this synchronously on every processed bid,
 * before the (eventually batched) Postgres flush, so the tick broadcaster (Phase 3) always has
 * a fresh value to read without touching the database.
 */
@Component
public class RedisPriceCache implements PriceCache {

    private static final String DIRTY_SET_KEY = "auctions:dirty";
    private static final Duration TTL_BUFFER = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;

    public RedisPriceCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void setCurrent(UUID auctionId, Money price, UUID winnerId, Instant endTime) {
        String key = currentKey(auctionId);
        Map<String, String> fields = new HashMap<>();
        fields.put("price", price.amount().toPlainString());
        fields.put("winnerId", winnerId == null ? "" : winnerId.toString());
        fields.put("endTime", endTime.toString());
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expireAt(key, endTime.plus(TTL_BUFFER));
    }

    @Override
    public void markDirty(UUID auctionId) {
        redisTemplate.opsForSet().add(DIRTY_SET_KEY, auctionId.toString());
    }

    public static String currentKey(UUID auctionId) {
        return "auction:" + auctionId + ":current";
    }
}
