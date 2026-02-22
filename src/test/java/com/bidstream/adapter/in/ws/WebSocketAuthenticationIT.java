package com.bidstream.adapter.in.ws;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.restassured.RestAssured;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Verifies the STOMP CONNECT frame is authenticated via the same JWT used for REST (PDR §15.1).
 * Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WebSocketAuthenticationIT {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private String registerAndLogin() {
        String username = "wsuser-" + UUID.randomUUID().toString().substring(0, 8);
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

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        return client;
    }

    @Test
    void connectWithValidJwtSucceeds() throws Exception {
        String token = registerAndLogin();
        WebSocketStompClient client = stompClient();
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        client.connectAsync("ws://localhost:" + port + "/ws", (org.springframework.web.socket.WebSocketHttpHeaders) null, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }
                });

        StompSession session = sessionFuture.get(10, TimeUnit.SECONDS);
        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void connectWithoutTokenIsRejected() throws Exception {
        WebSocketStompClient client = stompClient();

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        client.connectAsync("ws://localhost:" + port + "/ws", (org.springframework.web.socket.WebSocketHttpHeaders) null, new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }
                });

        // The interceptor throws on CONNECT without a Bearer token, so the client should never
        // reach afterConnected - the future must still be incomplete once transport round-trip
        // time has clearly passed.
        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
