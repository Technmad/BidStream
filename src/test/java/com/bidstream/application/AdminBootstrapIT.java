package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PDR §17.1: the only way any account can ever be granted {@code ROLE_ADMIN} is by registering
 * as the exact username configured in {@code bidstream.admin.bootstrap-username}. A dedicated
 * property value (a separate Spring context from every other IT, which all run with it unset)
 * proves both halves: the configured username gets promoted, and nobody else does. Runs against
 * the local dev stack's real Postgres (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "bidstream.admin.bootstrap-username=qa-bootstrap-admin")
class AdminBootstrapIT {

    private static final String BOOTSTRAP_USERNAME = "qa-bootstrap-admin";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'qa-bootstrap-%' OR username LIKE 'qa-not-admin-%'");
    }

    @Test
    void registeringAsTheConfiguredBootstrapUsernameGrantsRoleAdmin() {
        given().contentType("application/json")
                .body("{\"username\":\"" + BOOTSTRAP_USERNAME + "\",\"email\":\"" + BOOTSTRAP_USERNAME
                        + "@example.com\",\"password\":\"password123\"}")
                .post("/api/v1/auth/register")
                .then().statusCode(201);

        String accessToken = given().contentType("application/json")
                .body("{\"username\":\"" + BOOTSTRAP_USERNAME + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");

        assertThat(rolesInToken(accessToken)).contains("ROLE_ADMIN", "ROLE_USER", "ROLE_SELLER");
    }

    @Test
    void registeringAsAnyoneElseNeverGrantsRoleAdmin() {
        String username = "qa-not-admin-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"email\":\"" + username
                        + "@example.com\",\"password\":\"password123\"}")
                .post("/api/v1/auth/register")
                .then().statusCode(201);

        String accessToken = given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");

        assertThat(rolesInToken(accessToken)).doesNotContain("ROLE_ADMIN");
    }

    @SuppressWarnings("unchecked")
    private List<String> rolesInToken(String jwt) {
        String[] parts = jwt.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        try {
            Map<String, Object> claims = new ObjectMapper().readValue(payloadJson, Map.class);
            return (List<String>) claims.get("roles");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
