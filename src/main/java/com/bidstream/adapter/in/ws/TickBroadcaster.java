package com.bidstream.adapter.in.ws;

import com.bidstream.adapter.in.ws.dto.PriceUpdateMessage;
import com.bidstream.adapter.out.cache.RedisPriceCache;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-node broadcast ticker (PDR §15.3): coalesces however many bids landed in the last tick
 * into a single {@code PRICE_UPDATE} per auction, decoupling broadcast volume from bid volume.
 * Reads only Redis - never Postgres, never Kafka - so it stays cheap even under a hot auction.
 * Each node runs its own ticker and pushes only to its own locally-connected sessions, so no
 * cross-node coordination or shared broker is needed.
 */
@Component
public class TickBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);
    private static final String DIRTY_SET_KEY = "auctions:dirty";
    private static final long DRAIN_BATCH_SIZE = 500;

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    public TickBroadcaster(StringRedisTemplate redisTemplate, SimpMessagingTemplate messagingTemplate) {
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedRateString = "${bidstream.broadcast.tick-interval-ms:250}")
    public void tick() {
        List<String> dirtyAuctionIds = redisTemplate.opsForSet().pop(DIRTY_SET_KEY, DRAIN_BATCH_SIZE);
        if (dirtyAuctionIds == null || dirtyAuctionIds.isEmpty()) {
            return;
        }
        for (String rawId : dirtyAuctionIds) {
            broadcastCurrentPrice(rawId);
        }
    }

    private void broadcastCurrentPrice(String rawAuctionId) {
        try {
            UUID auctionId = UUID.fromString(rawAuctionId);
            String key = RedisPriceCache.currentKey(auctionId);
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);
            if (fields.isEmpty()) {
                return; // evicted/expired between mark-dirty and this tick - nothing to send
            }
            String price = (String) fields.get("price");
            String winnerId = (String) fields.get("winnerId");
            Instant endTime = Instant.parse((String) fields.get("endTime"));

            PriceUpdateMessage message = PriceUpdateMessage.of(auctionId, price, winnerId, endTime);
            messagingTemplate.convertAndSend("/topic/auctions/" + auctionId, message);
        } catch (Exception e) {
            log.warn("Failed to broadcast price update for auctionId={}", rawAuctionId, e);
        }
    }
}
