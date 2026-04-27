package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import java.sql.Timestamp;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Runs against the local dev stack (docker/docker-compose.yml) — see the note on
 * {@link AuthControllerIT} for why this isn't an embedded Testcontainers instance.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuctionControllerIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

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

    private String createAuctionRequest() {
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        return "{\"title\":\"Vintage Camera\",\"description\":\"Works great\","
                + "\"startingPrice\":50.00,\"minIncrement\":5.00,"
                + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}";
    }

    @Test
    void creatingAnAuctionRequiresAuthentication() {
        given().contentType("application/json")
                .body(createAuctionRequest())
                .post("/api/v1/auctions")
                .then()
                .statusCode(403);
    }

    @Test
    void sellerCanCreateThenFetchTheirAuction() {
        String token = registerAndLogin("seller");

        String auctionId = given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(createAuctionRequest())
                .post("/api/v1/auctions")
                .then()
                .statusCode(201)
                .body("title", equalTo("Vintage Camera"))
                .body("status", equalTo("SCHEDULED"))
                .body("currentPrice", notNullValue())
                .extract().path("id");

        given()
                .get("/api/v1/auctions/" + auctionId)
                .then()
                .statusCode(200)
                .body("id", equalTo(auctionId));
    }

    @Test
    void gettingAnUnknownAuctionIsNotFound() {
        given()
                .get("/api/v1/auctions/" + UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void aUserWithoutTheSellerRoleCannotCreateAnAuction() {
        // Registration always grants ROLE_SELLER now (QA-REVIEW.md Critical finding: it never
        // existed at all before), so the only way to get a ROLE_USER-only token is to seed one
        // directly, bypassing the register endpoint - proving the SecurityConfig matcher is a
        // real, enforced gate, not just cosmetic.
        String username = uniqueUsername("buyeronly");
        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash, roles, created_at)
                VALUES (?, ?, ?, ?, ARRAY['ROLE_USER'], ?)
                """,
                UUID.randomUUID(), username, username + "@example.com",
                passwordEncoder.encode("password123"), Timestamp.from(Instant.now()));

        String token = given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(createAuctionRequest())
                .post("/api/v1/auctions")
                .then()
                .statusCode(403);
    }

    @Test
    void aNegativeReservePriceIsRejectedAtTheDtoLayerWithACleanBadRequest() {
        // QA-REVIEW.md Medium: reservePrice had no @DecimalMin, unlike startingPrice/minIncrement,
        // so a malformed value used to reach the DB layer relying solely on its CHECK constraint.
        String token = registerAndLogin("seller");
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);

        given()
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"title\":\"Bad Reserve Lot\",\"startingPrice\":50.00,\"reservePrice\":-10.00,"
                        + "\"minIncrement\":5.00,\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then()
                .statusCode(400);
    }

    @Test
    void onlyTheOwnerCanCancelAnAuction() {
        String ownerToken = registerAndLogin("owner");
        String otherToken = registerAndLogin("other");

        String auctionId = given()
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .body(createAuctionRequest())
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");

        given().header("Authorization", "Bearer " + otherToken)
                .post("/api/v1/auctions/" + auctionId + "/cancel")
                .then()
                .statusCode(403);

        given().header("Authorization", "Bearer " + ownerToken)
                .post("/api/v1/auctions/" + auctionId + "/cancel")
                .then()
                .statusCode(204);

        given()
                .get("/api/v1/auctions/" + auctionId)
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
    }

    @Test
    void listingAuctionsIsPublicAndPaginated() {
        given()
                .get("/api/v1/auctions?page=0&size=5")
                .then()
                .statusCode(200)
                .body("content", notNullValue());
    }
}
