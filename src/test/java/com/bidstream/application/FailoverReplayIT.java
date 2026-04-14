package com.bidstream.application;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.in.kafka.AuctionWorkingSet;
import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.domain.model.BidOutcome;
import io.restassured.RestAssured;
import java.math.BigDecimal;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PDR §22 must-have #2: a crash of the auction-processor must never lose, duplicate, or
 * contradict an outcome, no matter which of the two windows it hits (§9.6, §19):
 *
 * <ul>
 *   <li><b>Before flush</b> - the processor dies mid-transaction. Nothing it touched is durable,
 *       so the redelivered command must be processed exactly as if it had never been attempted.
 *       Modeled here by forcing the processor's own {@code @Transactional} method to roll back
 *       (a real Spring rollback, not a mock) and then replaying the identical command.</li>
 *   <li><b>After flush, before offset commit</b> - the DB transaction committed, but Kafka never
 *       learns the offset was consumed, so it redelivers the same message. Modeled by calling the
 *       processor directly a second time with the exact same {@code eventId}.</li>
 * </ul>
 *
 * Both are driven straight at {@link AuctionCommandProcessor} rather than through the Kafka
 * listener container, since the property under test is the processor's own idempotency/rollback
 * behavior - not Kafka's redelivery mechanics, which are the broker's problem, not ours. Runs
 * against the local dev stack's real Postgres (docker/docker-compose.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FailoverReplayIT {

    @LocalServerPort
    private int port;

    @Autowired
    private AuctionCommandProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuctionWorkingSet workingSet;

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

    private UUID createAuction(String sellerToken) {
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        String auctionId = given()
                .header("Authorization", "Bearer " + sellerToken)
                .contentType("application/json")
                .body("{\"title\":\"Failover Lot\",\"startingPrice\":100.00,\"minIncrement\":5.00,"
                        + "\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}")
                .post("/api/v1/auctions")
                .then().statusCode(201)
                .extract().path("id");
        return UUID.fromString(auctionId);
    }

    private BidCommand bidCommand(UUID auctionId, UUID bidderId, String amount) {
        return new BidCommand(UUID.randomUUID(), BidCommand.CURRENT_SCHEMA_VERSION, BidCommand.COMMAND_TYPE,
                auctionId, bidderId, new BigDecimal(amount), "USD", "MANUAL",
                UUID.randomUUID().toString(), Instant.now(), UUID.randomUUID());
    }

    @Test
    void aCrashBeforeFlushLeavesNoTraceAndTheRedeliveredCommandProcessesCleanly() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        UUID bidderId = extractUserId(bidderToken);
        UUID auctionId = createAuction(sellerToken);
        BidCommand cmd = bidCommand(auctionId, bidderId, "120.00");

        // Simulate the "died mid-transaction" window: run the processor inside a transaction we
        // deliberately roll back, exactly like the JVM disappearing before the flush commits.
        TransactionTemplate rollbackOnly = new TransactionTemplate(transactionManager);
        rollbackOnly.executeWithoutResult(status -> {
            processor.process(cmd);
            status.setRollbackOnly();
        });

        // Nothing from the crashed attempt may have survived durably.
        assertThat(countBids(auctionId)).isZero();
        assertThat(countProcessedEvents(cmd.eventId())).isZero();

        // A real JVM crash takes the old process's in-memory working set down with it; the
        // partition is picked up by a fresh consumer (or the same one, restarted) with a cold
        // cache, which reseeds from committed Postgres on next access. Model that handover
        // explicitly, since this test - unlike a real crash - is still in the same JVM whose
        // working set was mutated in-memory before the transaction below it was rolled back.
        workingSet.evict(auctionId);

        // Kafka's redelivery of the identical message must now process fully, as if for the
        // first time.
        BidDecisionWaiter.Decision decision = processor.process(cmd);
        assertThat(decision).isNotNull();
        assertThat(decision.outcome()).isInstanceOf(BidOutcome.Accepted.class);
        assertThat(countBids(auctionId)).isEqualTo(1);
        assertThat(countProcessedEvents(cmd.eventId())).isEqualTo(1);
    }

    @Test
    void aCrashAfterFlushButBeforeOffsetCommitCausesAHarmlessIdempotentReplay() {
        String sellerToken = registerAndLogin("seller");
        String bidderToken = registerAndLogin("bidder");
        UUID bidderId = extractUserId(bidderToken);
        UUID auctionId = createAuction(sellerToken);
        BidCommand cmd = bidCommand(auctionId, bidderId, "130.00");

        // First delivery: commits durably (DB flush succeeds).
        BidDecisionWaiter.Decision first = processor.process(cmd);
        assertThat(first).isNotNull();
        assertThat(first.outcome()).isInstanceOf(BidOutcome.Accepted.class);
        Long versionAfterFirst = jdbcTemplate.queryForObject(
                "SELECT version FROM auctions WHERE id = ?", Long.class, auctionId);

        // Simulate Kafka never having recorded the offset commit: it redelivers the exact same
        // message (same eventId). The processed_events dedup gate (PDR §9.6 rule 1) must make
        // this a complete no-op - not a second bid, not a second version bump.
        BidDecisionWaiter.Decision replay = processor.process(cmd);
        assertThat(replay).isNull();

        assertThat(countBids(auctionId)).isEqualTo(1);
        assertThat(countProcessedEvents(cmd.eventId())).isEqualTo(1);
        Long versionAfterReplay = jdbcTemplate.queryForObject(
                "SELECT version FROM auctions WHERE id = ?", Long.class, auctionId);
        assertThat(versionAfterReplay).isEqualTo(versionAfterFirst);
    }

    private int countBids(UUID auctionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bids WHERE auction_id = ?", Integer.class, auctionId);
        return count == null ? 0 : count;
    }

    private int countProcessedEvents(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private UUID extractUserId(String jwt) {
        String[] parts = jwt.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String sub = payloadJson.replaceAll(".*\"sub\":\"([0-9a-fA-F-]+)\".*", "$1");
        return UUID.fromString(sub);
    }
}
