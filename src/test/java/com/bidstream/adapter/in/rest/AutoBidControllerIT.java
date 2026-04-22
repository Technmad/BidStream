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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end proxy-bidding ladder, matching PDR §12.2's worked example, driven entirely through
 * the real HTTP + async Kafka pipeline. Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AutoBidControllerIT {

    @LocalServerPort
    private int port;

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

    private String createOpenAuction(String sellerToken, double startingPrice, double minIncrement) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        return given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Proxy Lot\",\"startingPrice\":" + startingPrice
                        + ",\"minIncrement\":" + minIncrement + ",\"startTime\":\"" + start
                        + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void worksThroughThePdrWorkedExampleEndToEnd() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        String carolToken = registerAndLogin("carol");
        String auctionId = createOpenAuction(sellerToken, 50.00, 5.00);

        // Alice sets auto-bid max = $100 -> price = $50, Alice winning (no competition yet).
        // Setting an auto-bid is itself a ladder event (PDR §12.1), so this resolves immediately.
        given().header("Authorization", "Bearer " + aliceToken)
                .contentType("application/json")
                .body("{\"maxAmount\":100.00}")
                .post("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(200)
                .body("maxAmount", org.hamcrest.Matchers.equalTo(100.00f));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(50.00f))
                        .body("currentWinnerId", org.hamcrest.Matchers.notNullValue()));

        // Bob sets auto-bid max = $80: Bob(80) <= Alice.max(100) -> Alice retains the lead.
        // price = min(100, 80 + 5) = $85, Alice winning, Bob outbid - resolved immediately too.
        given().header("Authorization", "Bearer " + bobToken)
                .contentType("application/json")
                .body("{\"maxAmount\":80.00}")
                .post("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(85.00f)));

        // Carol bids manual $120 - exceeds Alice's $100 max, so Carol takes the lead at
        // min(120, 100 + 5) = $105.
        given().header("Authorization", "Bearer " + carolToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":120.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids?wait=true")
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(105.00f)));
    }

    @Test
    void cancellingAnAutoBidStopsItFromCompeting() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        String auctionId = createOpenAuction(sellerToken, 50.00, 5.00);

        given().header("Authorization", "Bearer " + aliceToken)
                .contentType("application/json")
                .body("{\"maxAmount\":200.00}")
                .post("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + aliceToken)
                .delete("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + bobToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":55.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids?wait=true")
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        // Alice's cancelled auto-bid must NOT counter-bid Bob.
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(55.00f)));
    }
}
