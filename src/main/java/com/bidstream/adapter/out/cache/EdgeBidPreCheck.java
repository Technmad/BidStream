package com.bidstream.adapter.out.cache;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Cheap, non-authoritative rejection of obviously-invalid bids before they're even published to
 * Kafka (PDR §9.3). A hint only - the authoritative check is always the single-writer processor;
 * this just sheds load. An atomic Lua script avoids a read-then-compare race against a
 * concurrently-updating hash.
 */
@Component
public class EdgeBidPreCheck {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local price = redis.call('HGET', KEYS[1], 'price')
            if price == false then return -1 end
            if tonumber(ARGV[1]) < tonumber(price) then return 0 end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public EdgeBidPreCheck(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Result check(UUID auctionId, BigDecimal bidAmount) {
        Long result = redisTemplate.execute(SCRIPT,
                List.of(RedisPriceCache.currentKey(auctionId)),
                bidAmount.toPlainString());
        if (result == null || result == -1) {
            return Result.UNKNOWN_LET_SERVER_DECIDE;
        }
        return result == 1 ? Result.PLAUSIBLY_VALID : Result.OBVIOUSLY_TOO_LOW;
    }

    public enum Result {
        PLAUSIBLY_VALID,
        OBVIOUSLY_TOO_LOW,
        UNKNOWN_LET_SERVER_DECIDE
    }
}
