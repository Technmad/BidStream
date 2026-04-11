package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.restassured.RestAssured;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The PDR §22 must-have concurrency test: fire many simultaneous bids at one auction and prove
 * the single-writer-per-partition design (§9.1) delivers exactly one winner, no lost accepted
 * bids, and a monotonically increasing, gap-free accepted-bid history - despite every bid
 * landing on Kafka at effectively the same instant from many concurrent threads. Runs against
 * the local dev stack's real Postgres/Kafka/Redis (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConcurrentBiddingIT {

    private static final int BIDDER_COUNT = 1000;

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /** Bypasses the (slow, bcrypt-hashing) register endpoint for 1000 throwaway bidders. */
    private List<UUID> insertBidders(int count) {
        List<UUID> ids = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO users (id, username, email, password_hash, roles, created_at)
                    VALUES (?, ?, ?, 'x', ARRAY['ROLE_USER'], ?)
                    """,
                    id, "concurrent-" + id, "concurrent-" + id + "@example.com",
                    Timestamp.from(Instant.now()));
            ids.add(id);
        }
        return ids;
    }

    @Test
    void oneThousandConcurrentBidsYieldExactlyOneWinnerAndAGapFreeHistory() throws Exception {
        String sellerToken = registerAndLogin("seller");
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Concurrency Lot\",\"startingPrice\":100.00,\"minIncrement\":1.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);

        List<UUID> bidders = insertBidders(BIDDER_COUNT);

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch ready = new CountDownLatch(BIDDER_COUNT);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < BIDDER_COUNT; i++) {
            UUID bidderId = bidders.get(i);
            // Every bid amount is distinct and increasing with i, so whichever ends up decided
            // last among a set of already-accepted, still-competitive bids can meaningfully win.
            BigDecimal amount = new BigDecimal(150 + i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                submitBidCommandUseCase.submit(auctionUuid, bidderId, amount, "USD",
                        UUID.randomUUID().toString());
            });
        }
        ready.await(30, TimeUnit.SECONDS);
        go.countDown(); // release all 1000 submissions at effectively the same instant
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // Wait for the single-writer processor to work through the whole burst.
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            Integer acceptedCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM bids WHERE auction_id = ? AND status = 'ACCEPTED'",
                    Integer.class, auctionUuid);
            // The highest-amount bid (150+999=1149) is guaranteed to be accepted whenever it's
            // processed, since nothing can ever outrank it - so its presence signals the burst
            // has fully drained.
            Integer highestPresent = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM bids WHERE auction_id = ? AND amount = 1149.0000",
                    Integer.class, auctionUuid);
            assertThat(highestPresent).isEqualTo(1);
            assertThat(acceptedCount).isGreaterThan(0);
        });

        // Exactly one current winner, and it must be the highest-amount bidder (1149), since a
        // strictly higher bid can never lose to an earlier-processed lower one.
        given().get("/api/v1/auctions/" + auctionId)
                .then()
                .body("currentPrice", org.hamcrest.Matchers.equalTo(1149.00f))
                .body("currentWinnerId", org.hamcrest.Matchers.equalTo(bidders.get(999).toString()));

        // Gap-free, monotonically increasing history in TRUE processing order. Note: bids.
        // created_at is the client-stamped occurredAt (edge-stamped once, before publish, per
        // PDR §8) - under 1000 threads racing to publish, scheduling jitter means occurredAt
        // order can differ from the order commands actually land in the Kafka log. The outbox
        // row for each accepted bid is written in the SAME transaction as the bid itself, so
        // its BIGSERIAL id is a reliable proxy for actual single-writer processing order.
        List<java.math.BigDecimal> amountsInOrder = jdbcTemplate.queryForList("""
                SELECT b.amount FROM bids b
                  JOIN outbox o ON o.topic = 'bids.accepted'
                                AND (o.payload ->> 'bidId')::uuid = b.id
                 WHERE b.auction_id = ? AND b.status = 'ACCEPTED'
                 ORDER BY o.id ASC
                """, java.math.BigDecimal.class, auctionUuid);
        assertThat(amountsInOrder).isNotEmpty();
        for (int i = 1; i < amountsInOrder.size(); i++) {
            assertThat(amountsInOrder.get(i))
                    .as("accepted bid #%d must exceed the previously *processed* accepted bid", i)
                    .isGreaterThan(amountsInOrder.get(i - 1));
        }

        // The auction's version must have advanced exactly once per accepted bid - no lost or
        // double-applied update slipped past the optimistic-lock backstop (PDR §9.2, §22).
        Integer acceptedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bids WHERE auction_id = ? AND status = 'ACCEPTED'",
                Integer.class, auctionUuid);
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM auctions WHERE id = ?", Long.class, auctionUuid);
        assertThat(version).isEqualTo((long) acceptedCount);
    }
}
