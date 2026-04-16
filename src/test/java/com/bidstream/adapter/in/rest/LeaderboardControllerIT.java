package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

import io.restassured.RestAssured;
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
 * End-to-end: place bids through the real async pipeline, confirm the leaderboard (PDR §13)
 * ends up ranking bidders by their own highest bid, highest first. Runs against the local dev
 * stack's real Postgres/Kafka/Redis (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LeaderboardControllerIT {

    @LocalServerPort
    private int port;

    @Autowired
    private com.bidstream.application.SubmitBidCommandUseCase submitBidCommandUseCase;

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
    void leaderboardRanksBiddersByTheirOwnHighestBidDescending() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        UUID aliceId = extractUserId(aliceToken);
        UUID bobId = extractUserId(bobToken);

        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Leaderboard Lot\",\"startingPrice\":100.00,\"minIncrement\":1.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);

        // Alice bids twice (her leaderboard entry must reflect only her HIGHEST bid); Bob bids
        // once, lower than Alice's max but higher than Alice's first bid.
        submitBidCommandUseCase.submit(auctionUuid, aliceId, new java.math.BigDecimal("110.00"),
                "USD", UUID.randomUUID().toString());
        submitBidCommandUseCase.submit(auctionUuid, bobId, new java.math.BigDecimal("130.00"),
                "USD", UUID.randomUUID().toString());
        submitBidCommandUseCase.submit(auctionUuid, aliceId, new java.math.BigDecimal("150.00"),
                "USD", UUID.randomUUID().toString());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId + "/leaderboard")
                        .then()
                        .body("size()", org.hamcrest.Matchers.equalTo(2))
                        .body("[0].bidderId", org.hamcrest.Matchers.equalTo(aliceId.toString()))
                        .body("[0].amount", org.hamcrest.Matchers.equalTo(150.00f))
                        .body("[1].bidderId", org.hamcrest.Matchers.equalTo(bobId.toString()))
                        .body("[1].amount", org.hamcrest.Matchers.equalTo(130.00f)));
    }
}
