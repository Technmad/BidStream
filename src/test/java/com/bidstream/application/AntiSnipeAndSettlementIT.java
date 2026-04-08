package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

/**
 * End-to-end anti-snipe extension (PDR §11.2) and inline settlement (PDR §11.3) via the real
 * HTTP + async pipeline, including the close-trigger scheduler. Runs against the local dev
 * stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AntiSnipeAndSettlementIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

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
    void bidWithinAntiSnipeWindowExtendsTheAuctionAndDelaysAutomaticClose() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        UUID bidderId = extractUserId(bidderToken);

        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant originalEnd = Instant.now().plusSeconds(8);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Snipe Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"antiSnipeSeconds\":10,\"startTime\":\"" + start + "\",\"endTime\":\""
                        + originalEnd + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);

        // This bid lands well within the 10s anti-snipe window of the 8s-away end time.
        submitBidCommandUseCase.submit(auctionUuid, bidderId, new BigDecimal("55.00"), "USD",
                UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("status", org.hamcrest.Matchers.equalTo("EXTENDED")));

        String extendedEndStr = given().get("/api/v1/auctions/" + auctionId)
                .then().extract().path("endTime");
        Instant extendedEnd = Instant.parse(extendedEndStr);
        assertThat(extendedEnd).isAfter(originalEnd);

        // The scheduler's scan reads the CURRENT (extended) end_time, so the auction must still
        // be open once the original end time has passed but the extended one hasn't yet - proves
        // the close was genuinely delayed, not just eventually settled.
        await().atMost(Duration.ofSeconds(
                        Duration.between(Instant.now(), originalEnd).getSeconds() + 3))
                .untilAsserted(() -> assertThat(Instant.now()).isAfter(originalEnd));
        given().get("/api/v1/auctions/" + auctionId)
                .then()
                .body("status", org.hamcrest.Matchers.equalTo("EXTENDED"));

        // Once the extended end time genuinely passes, the scheduler closes it and settles SOLD
        // (no reserve was set, and there's a winning bid).
        await().atMost(Duration.ofSeconds(150)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("status", org.hamcrest.Matchers.equalTo("SOLD"))
                        .body("currentWinnerId", org.hamcrest.Matchers.equalTo(bidderId.toString())));
    }
}
