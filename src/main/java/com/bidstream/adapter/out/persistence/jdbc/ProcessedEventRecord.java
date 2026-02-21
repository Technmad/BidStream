package com.bidstream.adapter.out.persistence.jdbc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of the {@code processed_events} dedup ledger (PDR §8, §9.6) — the authority for "have I
 * already processed command E?" and the stored outcome a replay re-asserts instead of deciding.
 */
public record ProcessedEventRecord(
        UUID eventId,
        UUID auctionId,
        String outcome,
        String rejectReason,
        BigDecimal finalPrice,
        UUID winnerId,
        Instant occurredAt) {
}
