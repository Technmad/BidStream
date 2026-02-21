package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.domain.port.EventPublisher;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end: publish a BidCommand onto the real Kafka broker and verify the auction-processor
 * (running as part of this same Spring context) decides it, updates Postgres, and writes both
 * the dedup ledger row and the outbox event - all inside the single write transaction (PDR §9.6).
 * Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuctionCommandProcessorIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventPublisher eventPublisher;

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

    private String createOpenAuction(String sellerToken) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        return given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Async Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
    }

    private UUID extractUserId(String jwt) {
        // The subject claim is the user id - decode without verifying (test-only convenience).
        String[] parts = jwt.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String sub = payloadJson.replaceAll(".*\"sub\":\"([0-9a-fA-F-]+)\".*", "$1");
        return UUID.fromString(sub);
    }

    @Test
    void publishedBidCommandIsDecidedPersistedAndLeavesAnAuditTrail() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken);
        UUID bidderId = extractUserId(bidderToken);

        var command = submitBidCommandUseCase.submit(
                UUID.fromString(auctionId), bidderId, new BigDecimal("55.00"), "USD",
                UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(55.00f))
                        .body("currentWinnerId", org.hamcrest.Matchers.equalTo(bidderId.toString())));

        Integer processedEventCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ? AND outcome = 'ACCEPTED'",
                Integer.class, command.eventId());
        assertThat(processedEventCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic = 'bids.accepted' AND aggregate_id = ?",
                Integer.class, UUID.fromString(auctionId));
        assertThat(outboxCount).isGreaterThanOrEqualTo(1);

        Integer bidRowCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bids WHERE auction_id = ? AND bidder_id = ? AND status = 'ACCEPTED'",
                Integer.class, UUID.fromString(auctionId), bidderId);
        assertThat(bidRowCount).isEqualTo(1);
    }

    @Test
    void redeliveringTheSameEventIdDoesNotReDecideOrDuplicateTheBid() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken);
        UUID bidderId = extractUserId(bidderToken);
        UUID auctionUuid = UUID.fromString(auctionId);

        BidCommand command = submitBidCommandUseCase.submit(
                auctionUuid, bidderId, new BigDecimal("60.00"), "USD", UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, command.eventId())).isEqualTo(1));

        // Simulate a Kafka redelivery: the exact same command (same eventId/occurredAt) lands
        // again, as would happen after a partition reassignment replays un-committed offsets.
        eventPublisher.publish("auction.commands", auctionUuid.toString(), command);

        // Give the redelivery time to be consumed, then assert nothing changed: no re-decision
        // (still winning at 60.00, not rejected as ALREADY_HIGHEST), no duplicate bid row.
        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer processedEventCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM processed_events WHERE event_id = ?",
                    Integer.class, command.eventId());
            assertThat(processedEventCount).isEqualTo(1);

            Integer bidRowCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM bids WHERE auction_id = ? AND bidder_id = ?",
                    Integer.class, auctionUuid, bidderId);
            assertThat(bidRowCount).isEqualTo(1);

            given().get("/api/v1/auctions/" + auctionId)
                    .then()
                    .body("currentWinnerId", org.hamcrest.Matchers.equalTo(bidderId.toString()))
                    .body("currentPrice", org.hamcrest.Matchers.equalTo(60.00f));
        });
    }
}
