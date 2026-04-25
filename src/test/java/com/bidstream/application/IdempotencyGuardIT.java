package com.bidstream.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.adapter.messaging.dto.BidCommand;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PDR §13/§19's idempotency guard, at the layer that's actually authoritative. The scenario this
 * covers is one {@code bids}' own unique constraint can never catch (docs/adr/0005): two
 * genuinely different commands - different {@code eventId}, different {@code occurredAt} - that
 * happen to reuse the same client {@code Idempotency-Key}. That's not a Kafka redelivery (the
 * processed_events/eventId replay gate doesn't fire), and it's not caught by
 * {@code UNIQUE (auction_id, bidder_id, idempotency_key, created_at)} either, since created_at
 * differs between the two. Runs against the local dev stack's real Postgres
 * (docker/docker-compose.yml).
 */
@SpringBootTest
class IdempotencyGuardIT {

    @Autowired
    private AuctionCommandProcessor processor;

    @Autowired
    private AuctionService auctionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID insertUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, username, email, password_hash, roles, created_at)
                VALUES (?, ?, ?, 'x', ARRAY['ROLE_USER'], ?)
                """,
                id, prefix + "-" + id, prefix + "-" + id + "@example.com", Timestamp.from(Instant.now()));
        return id;
    }

    private BidCommand bidCommand(UUID auctionId, UUID bidderId, String amount, String idempotencyKey) {
        return new BidCommand(UUID.randomUUID(), BidCommand.CURRENT_SCHEMA_VERSION, BidCommand.COMMAND_TYPE,
                auctionId, bidderId, new BigDecimal(amount), "USD", "MANUAL", idempotencyKey,
                Instant.now(), UUID.randomUUID());
    }

    @Test
    void aSecondCommandReusingTheSameIdempotencyKeyIsRejectedEvenWithADifferentEventId() {
        UUID sellerId = insertUser("seller");
        UUID bidderId = insertUser("bidder");
        Currency usd = Currency.getInstance("USD");
        UUID auctionId = auctionService.create(sellerId, null, "Idempotency Lot", "desc",
                Money.of(new BigDecimal("50.00"), usd), null, Money.of(new BigDecimal("5.00"), usd),
                Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now().plus(1, ChronoUnit.HOURS), 30)
                .id();
        String sharedIdempotencyKey = UUID.randomUUID().toString();

        // First command: a genuine new bid, accepted normally.
        BidCommand first = bidCommand(auctionId, bidderId, "60.00", sharedIdempotencyKey);
        BidDecisionWaiter.Decision firstDecision = processor.process(first);
        assertThat(firstDecision.outcome()).isInstanceOf(BidOutcome.Accepted.class);

        // Second command: a DIFFERENT eventId/occurredAt (a genuine second HTTP request, not a
        // Kafka redelivery), but the SAME idempotency key. bids' own unique constraint would let
        // this straight through (different created_at) - only the dedicated table catches it.
        BidCommand second = bidCommand(auctionId, bidderId, "70.00", sharedIdempotencyKey);
        BidDecisionWaiter.Decision secondDecision = processor.process(second);

        assertThat(secondDecision.outcome()).isInstanceOf(BidOutcome.Rejected.class);
        assertThat(((BidOutcome.Rejected) secondDecision.outcome()).reason())
                .isEqualTo(com.bidstream.domain.model.BidRejectReason.DUPLICATE_IDEMPOTENCY_KEY);

        Integer bidCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bids WHERE auction_id = ?", Integer.class, auctionId);
        assertThat(bidCount).isEqualTo(1);

        Integer keyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bid_idempotency_keys WHERE auction_id = ? AND bidder_id = ? AND idempotency_key = ?",
                Integer.class, auctionId, bidderId, sharedIdempotencyKey);
        assertThat(keyCount).isEqualTo(1);
    }
}
