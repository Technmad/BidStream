package com.bidstream.adapter.in.ws;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.messaging.dto.CloseCommand;
import com.bidstream.application.SubmitBidCommandUseCase;
import com.bidstream.domain.port.EventPublisher;
import io.restassured.RestAssured;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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

/**
 * PDR §15.2's two broadcast-only lifecycle messages - AUCTION_EXTENDED and AUCTION_ENDED - never
 * reached a subscribed client before this fix (QA-REVIEW.md Critical + High findings: nothing
 * consumed {@code auctions.events}, and an anti-snipe extension was silent). Runs against the
 * local dev stack's real Postgres/Kafka/Redis/WebSocket (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AuctionLifecycleBroadcastIT {

    @LocalServerPort
    private int port;

    @Autowired
    private SubmitBidCommandUseCase submitBidCommandUseCase;

    @Autowired
    private EventPublisher eventPublisher;

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

    /** Subscribes to {@code destination} inside {@code afterConnected}, exactly like
     * TickBroadcasterIT - the trigger must not fire until the subscription is actually in
     * place, and subscribing synchronously in the connect callback is what guarantees that. */
    private BlockingQueue<Object> connectAndSubscribe(String token, String destination) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null,
                connectHeaders, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        session.subscribe(destination, new StompFrameHandler() {
                            @Override
                            public Class<?> getPayloadType(StompHeaders headers) {
                                return Map.class;
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                received.add(payload);
                            }
                        });
                        sessionFuture.complete(session);
                    }
                });
        sessionFuture.get(10, TimeUnit.SECONDS);
        return received;
    }

    @Test
    void aBidWithinTheAntiSnipeWindowBroadcastsAuctionExtended() throws Exception {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        UUID bidderId = extractUserId(bidderToken);

        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plusSeconds(8);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Broadcast Snipe Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"antiSnipeSeconds\":10,\"startTime\":\"" + start + "\",\"endTime\":\""
                        + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);

        BlockingQueue<Object> received = connectAndSubscribe(bidderToken, "/topic/auctions/" + auctionId);

        // Lands well within the 10s anti-snipe window of the 8s-away end time.
        submitBidCommandUseCase.submit(auctionUuid, bidderId, new BigDecimal("55.00"), "USD",
                UUID.randomUUID().toString());

        // The same topic also carries the ticker's own PRICE_UPDATE messages - skip those to
        // find the one-time AUCTION_EXTENDED broadcast this test is actually after.
        Map<String, Object> message = pollForType(received, "AUCTION_EXTENDED");

        assertThat(message).isNotNull();
        assertThat(message.get("auctionId")).isEqualTo(auctionId);
        assertThat(Instant.parse((String) message.get("newEndTime"))).isAfter(end);
    }

    private Map<String, Object> pollForType(BlockingQueue<Object> received, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            long remainingMs = deadline - System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) received.poll(remainingMs, TimeUnit.MILLISECONDS);
            if (message == null) {
                return null;
            }
            if (type.equals(message.get("type"))) {
                return message;
            }
        }
        return null;
    }

    @Test
    void closingAnAuctionBroadcastsAuctionEnded() throws Exception {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        UUID bidderId = extractUserId(bidderToken);

        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Broadcast Close Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"reservePrice\":50.00,\"startTime\":\"" + start + "\",\"endTime\":\""
                        + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);

        submitBidCommandUseCase.submit(auctionUuid, bidderId, new BigDecimal("60.00"), "USD",
                UUID.randomUUID().toString());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then().body("currentPrice", org.hamcrest.Matchers.equalTo(60.00f)));

        BlockingQueue<Object> received = connectAndSubscribe(sellerToken, "/topic/auctions/" + auctionId);

        Instant scheduledEnd = Instant.parse(
                given().get("/api/v1/auctions/" + auctionId).then().extract().path("endTime"));
        eventPublisher.publish("auction.commands", auctionId, CloseCommand.of(auctionUuid, scheduledEnd));

        // Same race as the AUCTION_EXTENDED test above - the ticker's own PRICE_UPDATE messages
        // share this topic, so skip those to find the one-time AUCTION_ENDED broadcast.
        Map<String, Object> message = pollForType(received, "AUCTION_ENDED");

        assertThat(message).isNotNull();
        assertThat(message.get("auctionId")).isEqualTo(auctionId);
        assertThat(message.get("outcome")).isEqualTo("SOLD");
        assertThat(message.get("winnerId")).isEqualTo(bidderId.toString());
        assertThat(((Number) Double.valueOf(message.get("finalPrice").toString())).doubleValue())
                .isEqualTo(60.00);
    }
}
