package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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
class BidControllerIT {

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
                .body("{\"title\":\"Guitar\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .body("status", equalTo("OPEN"))
                .extract().path("id");
    }

    @Test
    void validBidIsAcceptedAndBecomesTheNewCurrentPrice() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken);

        given()
                .header("Authorization", "Bearer " + bidderToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":55.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACCEPTED"))
                .body("newPrice", equalTo(55.00f));

        given()
                .get("/api/v1/auctions/" + auctionId)
                .then()
                .statusCode(200)
                .body("currentPrice", equalTo(55.00f))
                .body("version", equalTo(1));
    }

    @Test
    void bidBelowMinIncrementIsRejectedWithReason() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken);

        given()
                .header("Authorization", "Bearer " + bidderToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":52.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(409)
                .body("reason", equalTo("BELOW_MIN_INCREMENT"));
    }

    @Test
    void sellerCannotBidOnTheirOwnAuction() {
        String sellerToken = registerAndLogin("seller");
        String auctionId = createOpenAuction(sellerToken);

        given()
                .header("Authorization", "Bearer " + sellerToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":60.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(409)
                .body("reason", equalTo("SELF_BID"));
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        String auctionId = createOpenAuction(sellerToken);
        String idempotencyKey = UUID.randomUUID().toString();

        given()
                .header("Authorization", "Bearer " + bidderToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body("{\"amount\":55.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + bidderToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .body("{\"amount\":60.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then()
                .statusCode(409);
    }

    @Test
    void secondBidderOutbidsTheFirst() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        String auctionId = createOpenAuction(sellerToken);

        given().header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body("{\"amount\":55.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + bobToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json").body("{\"amount\":65.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids")
                .then().statusCode(200)
                .body("newPrice", equalTo(65.00f));

        given()
                .get("/api/v1/auctions/" + auctionId)
                .then()
                .body("currentPrice", equalTo(65.00f))
                .body("version", equalTo(2));
    }
}
