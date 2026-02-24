package com.bidstream.adapter.in.ws;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.application.SubmitBidCommandUseCase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import io.restassured.RestAssured;

/**
 * Runs against the local dev stack (docker/docker-compose.yml). Places a bid through the real
 * async pipeline and asserts a watcher subscribed over STOMP receives a coalesced PRICE_UPDATE
 * within one broadcast tick (PDR §15.3), reading only from Redis.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TickBroadcasterIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

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

    private UUID extractUserId(String jwt) {
        String[] parts = jwt.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String sub = payloadJson.replaceAll(".*\"sub\":\"([0-9a-fA-F-]+)\".*", "$1");
        return UUID.fromString(sub);
    }

    @Test
    void watcherReceivesPriceUpdateAfterAnAcceptedBid() throws Exception {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Broadcast Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID bidderId = extractUserId(bidderToken);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + sellerToken);

        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null,
                connectHeaders, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        session.subscribe("/topic/auctions/" + auctionId, new StompFrameHandler() {
                            @Override
                            public Class<?> getPayloadType(StompHeaders headers) {
                                return java.util.Map.class;
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                received.add(payload);
                            }
                        });
                        sessionFuture.complete(session);
                    }
                });

        StompSession session = sessionFuture.get(10, TimeUnit.SECONDS);

        submitBidCommandUseCase.submit(UUID.fromString(auctionId), bidderId,
                new BigDecimal("55.00"), "USD", UUID.randomUUID().toString());

        @SuppressWarnings("unchecked")
        var message = (java.util.Map<String, Object>) received.poll(15, TimeUnit.SECONDS);
        session.disconnect();

        assertThat(message).isNotNull();
        assertThat(message.get("type")).isEqualTo("PRICE_UPDATE");
        assertThat(message.get("auctionId")).isEqualTo(auctionId);
        assertThat(((Number) Double.valueOf(message.get("price").toString())).doubleValue())
                .isEqualTo(55.00);
        assertThat(message.get("serverNow")).isNotNull();
    }
}
