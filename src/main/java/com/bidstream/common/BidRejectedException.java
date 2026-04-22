package com.bidstream.common;

import com.bidstream.domain.model.BidRejectReason;
import java.math.BigDecimal;

/**
 * A bid rejected before it ever reaches Kafka (an edge-side pre-check that already knows the
 * outcome, e.g. self-bid or an obviously-stale price). Mapped by {@link GlobalExceptionHandler}
 * to the exact same {@code {detail, reason, currentPrice, minIncrement}} shape the async-decided
 * rejection path already returns from {@code BidController.respond()} - a client shouldn't have
 * to handle two different rejection shapes depending on which layer caught it.
 */
public class BidRejectedException extends RuntimeException {

    private final BidRejectReason reason;
    private final BigDecimal currentPrice;
    private final BigDecimal minIncrement;

    public BidRejectedException(BidRejectReason reason) {
        this(reason, null, null);
    }

    public BidRejectedException(BidRejectReason reason, BigDecimal currentPrice, BigDecimal minIncrement) {
        super(reason.name());
        this.reason = reason;
        this.currentPrice = currentPrice;
        this.minIncrement = minIncrement;
    }

    public BidRejectReason reason() {
        return reason;
    }

    public BigDecimal currentPrice() {
        return currentPrice;
    }

    public BigDecimal minIncrement() {
        return minIncrement;
    }
}
