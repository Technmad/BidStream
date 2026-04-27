package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.adapter.out.cache.RedisPriceCache;
import com.bidstream.domain.model.BidOutcome;
import io.restassured.RestAssured;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * QA-REVIEW.md Medium: a redelivery of an already-processed event used to just log "replaying
 * stored outcome" and stop, never actually re-pushing anything to Redis - contradicting both its
 * own log line and PDR §19's "replay re-asserts the Redis projection." This reproduces the exact
 * failure scenario the finding describes: Redis loses the price key (a restart, an eviction, a
 * failover to an empty replica) between the original write and a later redelivery of that same
 * event. Runs against the local dev stack's real Postgres/Redis (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReplayReassertsRedisProjectionIT {

    @LocalServerPort
    private int port;

    @Autowired
    private AuctionCommandProcessor processor;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private String registerAndLogin(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.com\",\"password\":\"password123\"}")
                .post("/api/v1/auth/register")
                .then().statusCode(201);

        return given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    private UUID extractUserId(String jwt) {
        String[] parts = jwt.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String sub = payloadJson.replaceAll(".*\"sub\":\"([0-9a-fA-F-]+)\".*", "$1");
        return UUID.fromString(sub);
    }

    @Test
    void aRedeliveredAcceptedBidRestoresAPreviouslyLostRedisPriceKey() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        UUID bidderId = extractUserId(bidderToken);
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Replay Redis Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);

        BidCommand cmd = new BidCommand(UUID.randomUUID(), BidCommand.CURRENT_SCHEMA_VERSION,
                BidCommand.COMMAND_TYPE, auctionUuid, bidderId, new BigDecimal("70.00"), "USD",
                "MANUAL", UUID.randomUUID().toString(), Instant.now(), UUID.randomUUID());

        BidDecisionWaiter.Decision first = processor.process(cmd);
        assertThat(first).isNotNull();
        assertThat(first.outcome()).isInstanceOf(BidOutcome.Accepted.class);

        String redisKey = RedisPriceCache.currentKey(auctionUuid);
        assertThat(redisTemplate.opsForHash().get(redisKey, "price")).isNotNull();

        // Simulate the outage the finding describes: Redis loses the projection entirely.
        redisTemplate.delete(redisKey);
        assertThat(redisTemplate.hasKey(redisKey)).isFalse();

        // Kafka redelivers the exact same event (same eventId) - this must hit the dedup gate
        // and re-derive the Redis projection from committed Postgres, not just log and stop.
        BidDecisionWaiter.Decision replay = processor.process(cmd);
        assertThat(replay).isNull();

        Object restoredPrice = redisTemplate.opsForHash().get(redisKey, "price");
        assertThat(restoredPrice).isNotNull();
        assertThat(new BigDecimal((String) restoredPrice)).isEqualByComparingTo("70.00");
        Object restoredWinner = redisTemplate.opsForHash().get(redisKey, "winnerId");
        assertThat(restoredWinner).isEqualTo(bidderId.toString());
    }
}
