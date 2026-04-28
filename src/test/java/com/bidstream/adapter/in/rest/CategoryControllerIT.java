package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.restassured.RestAssured;
import java.sql.Timestamp;
import java.time.Instant;
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
 * PDR §14.4: the categories table has existed since V1 with no endpoint reading it until now.
 * Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CategoryControllerIT {

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

    /** Seeds a ROLE_ADMIN user directly, bypassing registration (§17.1 bootstrap is a separate concern). */
    private String registerAdminAndLogin() {
        String username = uniqueUsername("admin");
        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash, roles, created_at)
                VALUES (?, ?, ?, ?, ARRAY['ROLE_USER','ROLE_ADMIN'], ?)
                """,
                UUID.randomUUID(), username, username + "@example.com",
                passwordEncoder.encode("password123"), Timestamp.from(Instant.now()));

        return given().contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"password123\"}")
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    @Test
    void anyoneCanListCategoriesWithoutAuthentication() {
        String adminToken = registerAdminAndLogin();
        String categoryName = "Vintage Cameras " + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body("{\"name\":\"" + categoryName + "\"}")
                .post("/api/v1/categories")
                .then().statusCode(201);

        given().get("/api/v1/categories")
                .then().statusCode(200)
                .body("name", hasItem(categoryName));
    }

    @Test
    void aNonAdminCannotCreateACategory() {
        String bidderToken = registerAndLogin("bidder");

        given().header("Authorization", "Bearer " + bidderToken)
                .contentType("application/json")
                .body("{\"name\":\"Should Not Exist\"}")
                .post("/api/v1/categories")
                .then().statusCode(403);
    }

    @Test
    void anAdminCanCreateACategoryAndItsSlugIsDerivedFromTheName() {
        String adminToken = registerAdminAndLogin();
        String categoryName = "Retro Game Consoles " + UUID.randomUUID().toString().substring(0, 8);

        given().header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body("{\"name\":\"" + categoryName + "\"}")
                .post("/api/v1/categories")
                .then().statusCode(201)
                .body("name", equalTo(categoryName))
                .body("slug", equalTo(categoryName.toLowerCase().replaceAll("[^a-z0-9]+", "-")));
    }

    @Test
    void duplicateCategoryNameIsRejectedAsConflict() {
        String adminToken = registerAdminAndLogin();
        String categoryName = "Antique Furniture " + UUID.randomUUID().toString().substring(0, 8);
        given().header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body("{\"name\":\"" + categoryName + "\"}")
                .post("/api/v1/categories")
                .then().statusCode(201);

        given().header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body("{\"name\":\"" + categoryName + "\"}")
                .post("/api/v1/categories")
                .then().statusCode(409);
    }
}
