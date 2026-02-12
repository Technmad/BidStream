package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Full-stack integration test exercising the real Spring context, REST layer, JPA/Flyway, and
 * JWT issuance against a real Postgres instance. Runs against the project's local dev stack
 * (docker/docker-compose.yml, PDR §23.1) via the default application.yml datasource properties
 * rather than an embedded Testcontainers instance, so bring the stack up first:
 *
 * <pre>docker compose -f docker/docker-compose.yml up -d</pre>
 *
 * Usernames are randomized per run so repeated runs against the same persistent database never
 * collide on the unique username/email constraints.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuthControllerIT {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void registerThenLoginReturnsAccessAndRefreshTokens() {
        String username = uniqueUsername("bob");

        given()
                .contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.com\",\"password\":\"password123\"}")
                .post("/api/v1/auth/register")
                .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200)
                .body("accessToken", notNullValue())
                .body("refreshToken", notNullValue())
                .body("tokenType", equalTo("Bearer"));
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        String username = uniqueUsername("carol");

        given()
                .contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.com\",\"password\":\"password123\"}")
                .post("/api/v1/auth/register")
                .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}")
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401);
    }

    @Test
    void duplicateRegistrationIsConflict() {
        String username = uniqueUsername("dave");
        String body = "{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"password123\"}";

        given().contentType("application/json").body(body)
                .post("/api/v1/auth/register").then().statusCode(201);

        given().contentType("application/json").body(body)
                .post("/api/v1/auth/register").then().statusCode(409);
    }

    @Test
    void refreshTokenIssuesNewTokenPair() {
        String username = uniqueUsername("erin");

        given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.com\",\"password\":\"password123\"}")
                .post("/api/v1/auth/register").then().statusCode(201);

        String refreshToken = given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("refreshToken");

        given().contentType("application/json")
                .body("{\"refreshToken\":\"" + refreshToken + "\"}")
                .post("/api/v1/auth/refresh")
                .then()
                .statusCode(200)
                .body("accessToken", notNullValue());
    }
}
