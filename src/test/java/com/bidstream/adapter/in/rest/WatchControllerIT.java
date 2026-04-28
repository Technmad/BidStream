package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

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
 * PDR §14.4: watching is a durable bookmark decoupled from live WebSocket delivery - these tests
 * only cover the bookmark itself (POST/DELETE .../watch, GET /me/watching), not any WebSocket
 * behavior. Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WatchControllerIT {

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

    private String createAuction(String sellerToken, String title) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        return given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"" + title + "\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void watchingRequiresAuthentication() {
        String sellerToken = registerAndLogin("seller");
        String auctionId = createAuction(sellerToken, "Unauthenticated Watch Lot");

        given().post("/api/v1/auctions/" + auctionId + "/watch")
                .then().statusCode(403);
    }

    @Test
    void watchingTwiceIsIdempotentAndAppearsOnceInMyWatching() {
        String sellerToken = registerAndLogin("seller");
        String watcherToken = registerAndLogin("watcher");
        String title = "Idempotent Watch Lot " + UUID.randomUUID();
        String auctionId = createAuction(sellerToken, title);

        given().header("Authorization", "Bearer " + watcherToken)
                .post("/api/v1/auctions/" + auctionId + "/watch")
                .then().statusCode(204);
        given().header("Authorization", "Bearer " + watcherToken)
                .post("/api/v1/auctions/" + auctionId + "/watch")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + watcherToken)
                .get("/api/v1/me/watching")
                .then().statusCode(200)
                .body("content.title", hasItem(title))
                .body("totalElements", equalTo(1));
    }

    @Test
    void unwatchingSomethingNeverWatchedIsANoOpNotA404() {
        String sellerToken = registerAndLogin("seller");
        String watcherToken = registerAndLogin("watcher");
        String auctionId = createAuction(sellerToken, "Never Watched Lot");

        given().header("Authorization", "Bearer " + watcherToken)
                .delete("/api/v1/auctions/" + auctionId + "/watch")
                .then().statusCode(204);
    }

    @Test
    void unwatchingRemovesItFromMyWatching() {
        String sellerToken = registerAndLogin("seller");
        String watcherToken = registerAndLogin("watcher");
        String title = "Unwatch Me Lot " + UUID.randomUUID();
        String auctionId = createAuction(sellerToken, title);

        given().header("Authorization", "Bearer " + watcherToken)
                .post("/api/v1/auctions/" + auctionId + "/watch")
                .then().statusCode(204);
        given().header("Authorization", "Bearer " + watcherToken)
                .get("/api/v1/me/watching")
                .then().statusCode(200)
                .body("content.title", hasItem(title));

        given().header("Authorization", "Bearer " + watcherToken)
                .delete("/api/v1/auctions/" + auctionId + "/watch")
                .then().statusCode(204);
        given().header("Authorization", "Bearer " + watcherToken)
                .get("/api/v1/me/watching")
                .then().statusCode(200)
                .body("content.title", org.hamcrest.Matchers.not(hasItem(title)));
    }

    @Test
    void watchingAnUnknownAuctionIsNotFound() {
        String watcherToken = registerAndLogin("watcher");

        given().header("Authorization", "Bearer " + watcherToken)
                .post("/api/v1/auctions/" + UUID.randomUUID() + "/watch")
                .then().statusCode(404);
    }
}
