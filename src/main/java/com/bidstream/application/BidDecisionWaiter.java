package com.bidstream.application;

import com.bidstream.domain.model.BidOutcome;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Bridges the async pipeline back to a synchronous HTTP response for clients that want one (PDR
 * §14.2: "for clients that prefer synchronous UX, offer an optional short-lived server-side
 * wait"). The command is already durable in Kafka before any waiting starts, so a timeout here
 * only means the client didn't get the fast path - it never means the bid was lost.
 *
 * <p>This only resolves a wait when the processor that decided the command is the same JVM that
 * registered it - true for this single-instance setup. Once there is more than one app replica,
 * the WebSocket push (Phase 3) is the only channel guaranteed to reach the client regardless of
 * which node processed the command; this remains a nice-to-have fast path, never a requirement.
 */
@Component
public class BidDecisionWaiter {

    private final ConcurrentHashMap<UUID, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<Decision> register(UUID eventId) {
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(eventId, future);
        return future;
    }

    public void complete(UUID eventId, Decision decision) {
        CompletableFuture<Decision> future = pending.remove(eventId);
        if (future != null) {
            future.complete(decision);
        }
    }

    public record Decision(BidOutcome outcome, UUID bidId, BigDecimal currentPrice,
                            BigDecimal minIncrement) {

        public static Decision accepted(BidOutcome.Accepted outcome, UUID bidId) {
            return new Decision(outcome, bidId, null, null);
        }

        public static Decision rejected(BidOutcome.Rejected outcome, BigDecimal currentPrice,
                                         BigDecimal minIncrement) {
            return new Decision(outcome, null, currentPrice, minIncrement);
        }
    }
}
