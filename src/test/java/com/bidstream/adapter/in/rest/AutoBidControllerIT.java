package com.bidstream.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

import io.restassured.RestAssured;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end proxy-bidding ladder, matching PDR §12.2's worked example, driven entirely through
 * the real HTTP + async Kafka pipeline. Runs against the local dev stack (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AutoBidControllerIT {

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

    private String createOpenAuction(String sellerToken, double startingPrice, double minIncrement) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        return given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Proxy Lot\",\"startingPrice\":" + startingPrice
                        + ",\"minIncrement\":" + minIncrement + ",\"startTime\":\"" + start
                        + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void worksThroughThePdrWorkedExampleEndToEnd() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        String carolToken = registerAndLogin("carol");
        String auctionId = createOpenAuction(sellerToken, 50.00, 5.00);

        // Alice sets auto-bid max = $100 -> price = $50, Alice winning (no competition yet).
        // Setting an auto-bid is itself a ladder event (PDR §12.1), so this resolves immediately.
        given().header("Authorization", "Bearer " + aliceToken)
                .contentType("application/json")
                .body("{\"maxAmount\":100.00}")
                .post("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(200)
                .body("maxAmount", org.hamcrest.Matchers.equalTo(100.00f));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(50.00f))
                        .body("currentWinnerId", org.hamcrest.Matchers.notNullValue()));

        // Bob sets auto-bid max = $80: Bob(80) <= Alice.max(100) -> Alice retains the lead.
        // price = min(100, 80 + 5) = $85, Alice winning, Bob outbid - resolved immediately too.
        given().header("Authorization", "Bearer " + bobToken)
                .contentType("application/json")
                .body("{\"maxAmount\":80.00}")
                .post("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(85.00f)));

        // Carol bids manual $120 - exceeds Alice's $100 max, so Carol takes the lead at
        // min(120, 100 + 5) = $105.
        given().header("Authorization", "Bearer " + carolToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":120.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids?wait=true")
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(105.00f)));
    }

    @Test
    void cancellingAnAutoBidStopsItFromCompeting() {
        String sellerToken = registerAndLogin("seller");
        String aliceToken = registerAndLogin("alice");
        String bobToken = registerAndLogin("bob");
        String auctionId = createOpenAuction(sellerToken, 50.00, 5.00);

        given().header("Authorization", "Bearer " + aliceToken)
                .contentType("application/json")
                .body("{\"maxAmount\":200.00}")
                .post("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(200);

        given().header("Authorization", "Bearer " + aliceToken)
                .delete("/api/v1/auctions/" + auctionId + "/auto-bid")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + bobToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .body("{\"amount\":55.00}")
                .post("/api/v1/auctions/" + auctionId + "/bids?wait=true")
                .then().statusCode(200);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                given().get("/api/v1/auctions/" + auctionId)
                        .then()
                        // Alice's cancelled auto-bid must NOT counter-bid Bob.
                        .body("currentPrice", org.hamcrest.Matchers.equalTo(55.00f)));
    }

    /**
     * Regression test for the fix converting auto-bid-set into a real {@code auction.commands}
     * command (ADR-0002): before that fix, {@code AutoBidService} wrote the auction row directly
     * from the HTTP thread via an optimistic-lock write, racing the single-writer
     * {@code AuctionCommandProcessor} consuming manual bids off the same partition. Under
     * contention that race could make the processor's own optimistic-lock write lose, throwing
     * an uncaught {@code IllegalStateException} that - after 3 retries - routed a legitimate,
     * concurrently-submitted manual bid to {@code auction.commands.DLQ}, silently dropping it.
     *
     * <p>Now that setting an auto-bid also only mutates the auction aggregate via a command on
     * the same {@code auctionId}-keyed partition, Kafka's per-partition ordering serializes every
     * auto-bid-set against every manual bid for this auction - there is no longer any writer to
     * race. This fires many manual bids and auto-bid-sets at one auction simultaneously and
     * asserts nothing for this auction ever lands on the DLQ.
     */
    @Test
    void concurrentManualBidsAndAutoBidSetsNeverRouteToTheDlq() throws Exception {
        String sellerToken = registerAndLogin("seller");
        String auctionId = createOpenAuction(sellerToken, 50.00, 1.00);
        UUID auctionUuid = UUID.fromString(auctionId);

        int bidderCount = 30;
        List<String> tokens = new java.util.ArrayList<>();
        for (int i = 0; i < bidderCount; i++) {
            tokens.add(registerAndLogin("racer" + i));
        }

        KafkaConsumer<String, String> dlqConsumer = subscribeToDlq();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch ready = new CountDownLatch(bidderCount);
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < bidderCount; i++) {
                String token = tokens.get(i);
                boolean autoBid = i % 2 == 0;
                double amount = 60.00 + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    if (autoBid) {
                        given().header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .body("{\"maxAmount\":" + amount + "}")
                                .post("/api/v1/auctions/" + auctionId + "/auto-bid");
                    } else {
                        given().header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .body("{\"amount\":" + amount + "}")
                                .post("/api/v1/auctions/" + auctionId + "/bids");
                    }
                });
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            org.assertj.core.api.Assertions.assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // Let the single-writer processor fully drain the burst.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    given().get("/api/v1/auctions/" + auctionId)
                            .then()
                            .body("currentWinnerId", org.hamcrest.Matchers.notNullValue()));

            // A short grace window for a DLQ record to show up, if the old race were still present.
            assertNoDlqRecordFor(dlqConsumer, auctionUuid, Duration.ofSeconds(10));
        } finally {
            dlqConsumer.close();
        }
    }

    private KafkaConsumer<String, String> subscribeToDlq() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-autobid-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of("auction.commands.DLQ"));
        return consumer;
    }

    private void assertNoDlqRecordFor(KafkaConsumer<String, String> consumer, UUID auctionId, Duration window) {
        long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.value() != null && record.value().contains(auctionId.toString())) {
                    throw new AssertionError("Auction " + auctionId
                            + " had a command routed to the DLQ under concurrent auto-bid/manual-bid load: "
                            + record.value());
                }
            }
        }
    }
}
