package com.bidstream.adapter.out.cache;

import com.bidstream.domain.port.LeaderboardCache;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed leaderboard projection (PDR §13): a sorted set per auction, scored by each
 * bidder's own highest bid. {@code ZADD ... GT} makes "highest bid per user" hold even if this
 * is ever called out of order (e.g. a redelivered/replayed command) - a member's score can only
 * ever go up, never down, matching the domain invariant that price only increases.
 */
@Component
public class RedisLeaderboardCache implements LeaderboardCache {

    private static final DefaultRedisScript<Long> ZADD_GT_SCRIPT = new DefaultRedisScript<>(
            "return redis.call('ZADD', KEYS[1], 'GT', 'CH', ARGV[1], ARGV[2])", Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLeaderboardCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void recordBid(UUID auctionId, UUID bidderId, BigDecimal amount) {
        redisTemplate.execute(ZADD_GT_SCRIPT, List.of(leaderboardKey(auctionId)),
                amount.toPlainString(), bidderId.toString());
    }

    @Override
    public List<Entry> topN(UUID auctionId, int count) {
        Set<TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(leaderboardKey(auctionId), 0, count - 1);
        if (tuples == null) {
            return List.of();
        }
        List<Entry> ordered = new ArrayList<>(tuples.size());
        for (TypedTuple<String> tuple : tuples) {
            ordered.add(new Entry(UUID.fromString(tuple.getValue()), BigDecimal.valueOf(tuple.getScore())));
        }
        return ordered;
    }

    private static String leaderboardKey(UUID auctionId) {
        return "auction:" + auctionId + ":leaderboard";
    }
}
