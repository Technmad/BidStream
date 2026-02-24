package com.bidstream.adapter.out.cache;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bidstream.application.SubmitBidCommandUseCase;
import io.restassured.RestAssured;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RedisPriceCacheIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EdgeBidPreCheck edgeBidPreCheck;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerAndLogin(String prefix) {
        String username = uniqueUsername(prefix);
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
    void acceptedBidProjectsCurrentPriceToRedis() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Redis Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);
        UUID bidderId = extractUserId(bidderToken);

        submitBidCommandUseCase.submit(auctionUuid, bidderId, new BigDecimal("55.00"), "USD",
                UUID.randomUUID().toString());

        // Note: this app also runs the Phase-3 TickBroadcaster, which continuously drains
        // (SPOP) auctions:dirty - so membership there is inherently racy to assert against in
        // the same process and is covered end-to-end instead by TickBroadcasterIT. This test
        // only asserts the rebuildable projection itself: the price hash.
        String redisKey = RedisPriceCache.currentKey(auctionUuid);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String price = (String) redisTemplate.opsForHash().get(redisKey, "price");
            assertThat(price).isNotNull();
            assertThat(new java.math.BigDecimal(price)).isEqualByComparingTo("55.00");
        });
    }

    @Test
    void edgePreCheckRejectsAnAmountBelowTheCachedPrice() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Precheck Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);
        UUID bidderId = extractUserId(bidderToken);

        submitBidCommandUseCase.submit(auctionUuid, bidderId, new BigDecimal("60.00"), "USD",
                UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(edgeBidPreCheck.check(auctionUuid, new BigDecimal("50.00")))
                        .isEqualTo(EdgeBidPreCheck.Result.OBVIOUSLY_TOO_LOW));

        assertThat(edgeBidPreCheck.check(auctionUuid, new BigDecimal("100.00")))
                .isEqualTo(EdgeBidPreCheck.Result.PLAUSIBLY_VALID);

        assertThat(edgeBidPreCheck.check(UUID.randomUUID(), new BigDecimal("10.00")))
                .isEqualTo(EdgeBidPreCheck.Result.UNKNOWN_LET_SERVER_DECIDE);
    }
}
