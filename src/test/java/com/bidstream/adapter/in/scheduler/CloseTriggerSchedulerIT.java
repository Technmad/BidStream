package com.bidstream.adapter.in.scheduler;

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
 * The full lifecycle loop with no manual CLOSE publish at all: an auction whose end time has
 * already passed should be picked up by the scheduler's next scan and closed automatically.
 * Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CloseTriggerSchedulerIT {

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

    @Test
    void auctionPastItsEndTimeIsAutomaticallyClosedByTheScheduler() {
        String sellerToken = registerAndLogin("seller");
        // Starts already open, ends just 2s from now - by the scheduler's next scan (every 5s)
        // this will be "due" without any test code publishing a CloseCommand.
        Instant start = Instant.now().minus(2, ChronoUnit.MINUTES);
        Instant end = Instant.now().plusSeconds(2);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Scheduler Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");

        // No bids and reserve unmet (none set) -> should settle UNSOLD once the scheduler's next
        // scan (every 5s) picks it up, entirely without any test code publishing a CloseCommand.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("status", org.hamcrest.Matchers.equalTo("UNSOLD")));
    }
}
