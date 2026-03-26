package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bidstream.adapter.messaging.dto.CloseCommand;
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
 * End-to-end CLOSE handling (PDR §11.3): publishes a CLOSE command onto the real Kafka broker,
 * ordered on the same partition as bids, and verifies settlement + lifecycle event. Runs against
 * the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CloseCommandProcessorIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    @Autowired
    private EventPublisher eventPublisher;

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

    private UUID extractUserId(String jwt) {
        String[] parts = jwt.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String sub = payloadJson.replaceAll(".*\"sub\":\"([0-9a-fA-F-]+)\".*", "$1");
        return UUID.fromString(sub);
    }

    private String createOpenAuction(String sellerToken, double startingPrice, Double reservePrice) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String reserveJson = reservePrice == null ? "" : ",\"reservePrice\":" + reservePrice;
        return given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Close Lot\",\"startingPrice\":" + startingPrice
                        + ",\"minIncrement\":5.00" + reserveJson + ",\"startTime\":\"" + start
                        + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
    }

    private void publishClose(UUID auctionId, Instant scheduledEndTime) {
        CloseCommand command = CloseCommand.of(auctionId, scheduledEndTime);
        eventPublisher.publish("auction.commands", auctionId.toString(), command);
    }

    @Test
    void closingWithAWinningBidAboveReserveSettlesAsSold() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken, 50.00, 80.00);
        UUID auctionUuid = UUID.fromString(auctionId);
        UUID bidderId = extractUserId(bidderToken);

        submitBidCommandUseCase.submit(auctionUuid, bidderId, new BigDecimal("100.00"), "USD",
                UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then().body("currentPrice", org.hamcrest.Matchers.equalTo(100.00f)));

        Instant scheduledEnd = fetchEndTime(auctionId);
        publishClose(auctionUuid, scheduledEnd);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then().body("status", org.hamcrest.Matchers.equalTo("SOLD")));

        Integer settlementCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM settlements WHERE auction_id = ? AND outcome = 'SOLD' AND winner_id = ?",
                Integer.class, auctionUuid, bidderId);
        assertThat(settlementCount).isEqualTo(1);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic = 'auctions.events' AND aggregate_id = ?",
                Integer.class, auctionUuid);
        assertThat(outboxCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void closingBelowReserveWithNoWinningBidSettlesAsUnsold() {
        String sellerToken = registerAndLogin("seller");
        String auctionId = createOpenAuction(sellerToken, 50.00, 200.00);
        UUID auctionUuid = UUID.fromString(auctionId);

        Instant scheduledEnd = fetchEndTime(auctionId);
        publishClose(auctionUuid, scheduledEnd);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then().body("status", org.hamcrest.Matchers.equalTo("UNSOLD")));

        Integer settlementCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM settlements WHERE auction_id = ? AND outcome = 'UNSOLD'",
                Integer.class, auctionUuid);
        assertThat(settlementCount).isEqualTo(1);
    }

    @Test
    void duplicateCloseIsANoOp() {
        String sellerToken = registerAndLogin("seller");
        String auctionId = createOpenAuction(sellerToken, 50.00, null);
        UUID auctionUuid = UUID.fromString(auctionId);
        Instant scheduledEnd = fetchEndTime(auctionId);

        publishClose(auctionUuid, scheduledEnd);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM settlements WHERE auction_id = ?", Integer.class, auctionUuid))
                        .isEqualTo(1));

        // A second CLOSE for the already-terminal auction must not error or duplicate settlement.
        publishClose(auctionUuid, scheduledEnd);

        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM settlements WHERE auction_id = ?", Integer.class, auctionUuid))
                        .isEqualTo(1));
    }

    private Instant fetchEndTime(String auctionId) {
        String endTime = given().get("/api/v1/auctions/" + auctionId).then().extract().path("endTime");
        return Instant.parse(endTime);
    }
}
