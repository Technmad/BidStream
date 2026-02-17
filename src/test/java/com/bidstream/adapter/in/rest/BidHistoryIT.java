package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.restassured.RestAssured;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Runs against the local dev stack (docker/docker-compose.yml) — see the note on
 * {@link AuthControllerIT} for why this isn't an embedded Testcontainers instance.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BidHistoryIT {

    @LocalServerPort
    private int port;

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
                .body("{\"title\":\"Painting\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
    }

    private void placeBid(String token, String auctionId, double amount) {
        given()
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":" + amount + "}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(200);
    }

    @Test
    void auctionBidHistoryIsPublicAndOrderedNewestFirst() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        String auctionId = createOpenAuction(sellerToken);

        placeBid(aliceToken, auctionId, 55.00);
        placeBid(bobToken, auctionId, 65.00);

        given()
                .get("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(200)
                .body("content", hasSize(2))
                .body("content[0].amount", equalTo(65.00f))
                .body("content[0].status", equalTo("ACCEPTED"))
                .body("content[1].amount", equalTo(55.00f));
    }

    @Test
    void myBidsRequiresAuthenticationAndReturnsOwnActivity() {
        given()
                .get("/api/v1/me/bids")
                .then()
                .statusCode(403);

        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken);
        placeBid(bidderToken, auctionId, 60.00);

        given()
                .header("Authorization", "Bearer " + bidderToken)
                .get("/api/v1/me/bids")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].auctionId", equalTo(auctionId));
    }
}
