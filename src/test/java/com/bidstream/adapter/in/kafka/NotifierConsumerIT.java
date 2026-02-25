package com.bidstream.adapter.in.kafka;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.application.SubmitBidCommandUseCase;
import io.restassured.RestAssured;
import java.math.BigDecimal;
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
 * End-to-end: the auction-processor's outbox row is relayed to Kafka by {@code OutboxRelay},
 * consumed by {@code NotifierConsumer}, and pushed to the outbid user's personal STOMP queue.
 * Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class NotifierConsumerIT {

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

    private StompSession connectAndSubscribe(String token, BlockingQueue<Object> received) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null,
                connectHeaders, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
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
        return sessionFuture.get(10, TimeUnit.SECONDS);
    }

    @Test
    void outbidUserReceivesATargetedNotificationWhenSomeoneElseWins() throws Exception {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Notify Lot\",\"startingPrice\":50.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        UUID auctionUuid = UUID.fromString(auctionId);
        UUID aliceId = extractUserId(aliceToken);
        UUID bobId = extractUserId(bobToken);

        BlockingQueue<Object> aliceInbox = new LinkedBlockingQueue<>();
        StompSession aliceSession = connectAndSubscribe(aliceToken, aliceInbox);

        submitBidCommandUseCase.submit(auctionUuid, aliceId, new BigDecimal("55.00"), "USD",
                UUID.randomUUID().toString());

        @SuppressWarnings("unchecked")
        var aliceAcceptedMsg = (Map<String, Object>) aliceInbox.poll(15, TimeUnit.SECONDS);
        assertThat(aliceAcceptedMsg).isNotNull();
        assertThat(aliceAcceptedMsg.get("type")).isEqualTo("BID_RESULT");
        assertThat(aliceAcceptedMsg.get("status")).isEqualTo("ACCEPTED");

        submitBidCommandUseCase.submit(auctionUuid, bobId, new BigDecimal("65.00"), "USD",
                UUID.randomUUID().toString());

        @SuppressWarnings("unchecked")
        var aliceOutbidMsg = (Map<String, Object>) aliceInbox.poll(15, TimeUnit.SECONDS);
        aliceSession.disconnect();

        assertThat(aliceOutbidMsg).isNotNull();
        assertThat(aliceOutbidMsg.get("type")).isEqualTo("OUTBID");
        assertThat(aliceOutbidMsg.get("auctionId")).isEqualTo(auctionId);
        assertThat(Double.valueOf(aliceOutbidMsg.get("newPrice").toString())).isEqualTo(65.00);
    }
}
