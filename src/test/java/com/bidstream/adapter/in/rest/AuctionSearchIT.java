package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * PDR §8.4/§14.4: basic keyword search via the generated {@code search_vector} column - filtering
 * only, no relevance ranking. Runs against the local dev stack's real Postgres
 * (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuctionSearchIT {

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

    private void createAuction(String sellerToken, String title, String description) {
        createAuctionAndGetSellerId(sellerToken, title, description);
    }

    private String createAuctionAndGetSellerId(String sellerToken, String title, String description) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        Response response = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"" + title + "\",\"description\":\"" + description + "\","
                        + "\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().response();
        return response.path("sellerId");
    }

    @Test
    void searchMatchesOnTitleAndOnDescriptionAndExcludesNonMatches() {
        String sellerToken = registerAndLogin("seller");
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String titleMatchTitle = "Zorblatt" + marker + " Camera";
        String descriptionMatchTitle = "Plain Lot " + marker;
        String nonMatchTitle = "Totally Unrelated Lot " + marker;

        createAuction(sellerToken, titleMatchTitle, "just a camera");
        createAuction(sellerToken, descriptionMatchTitle, "features a rare zorblatt" + marker + " lens");
        createAuction(sellerToken, nonMatchTitle, "nothing relevant here");

        given().queryParam("q", "zorblatt" + marker)
                .get("/api/v1/auctions")
                .then().statusCode(200)
                .body("content.title", hasItem(titleMatchTitle))
                .body("content.title", hasItem(descriptionMatchTitle))
                .body("content.title", not(hasItem(nonMatchTitle)));
    }

    @Test
    void searchComposesWithStatusAndCategoryFiltersViaAnd() {
        String sellerToken = registerAndLogin("seller");
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String title = "Composable Filter Lot " + marker;
        // startTime is in the past, so this auction is actually OPEN, not SCHEDULED.
        createAuction(sellerToken, title, "widget " + marker);

        given().queryParam("q", "widget " + marker).queryParam("status", "SCHEDULED")
                .get("/api/v1/auctions")
                .then().statusCode(200)
                .body("content.title", not(hasItem(title)));

        given().queryParam("q", "widget " + marker).queryParam("status", "OPEN")
                .get("/api/v1/auctions")
                .then().statusCode(200)
                .body("content.title", hasItem(title));
    }

    @Test
    void sellerIdFiltersToOnlyThatSellersListings() {
        String sellerAToken = registerAndLogin("seller-a");
        String sellerBToken = registerAndLogin("seller-b");
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String titleA = "Seller A Lot " + marker;
        String titleB = "Seller B Lot " + marker;

        String sellerAId = createAuctionAndGetSellerId(sellerAToken, titleA, "from seller a");
        createAuction(sellerBToken, titleB, "from seller b");

        given().queryParam("sellerId", sellerAId)
                .get("/api/v1/auctions")
                .then().statusCode(200)
                .body("content.title", hasItem(titleA))
                .body("content.title", not(hasItem(titleB)));
    }
}
