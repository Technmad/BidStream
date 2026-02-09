package com.bidstream.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code Auction} aggregate root — the consistency boundary for all bid mutations (PDR §7).
 * Every invariant a bid must satisfy is enforced here, not in adapters, so no adapter can put
 * the aggregate in an invalid state.
 *
 * <p>This class is intentionally not thread-safe by itself; concurrency safety comes from the
 * single-writer-per-auction Kafka partitioning scheme (PDR §9), not from synchronization here.
 */
public final class AuctionItem {

    private final UUID id;
    private final UUID sellerId;
    private final UUID categoryId;
    private final String title;
    private final String description;
    private final Money startingPrice;
    private final Money reservePrice; // nullable
    private final Money minIncrement;
    private final Instant startTime;
    private final int antiSnipeSeconds;

    private Money currentPrice;
    private UUID currentWinnerId; // nullable
    private AuctionStatus status;
    private Instant endTime;
    private long version;

    public AuctionItem(UUID id, UUID sellerId, UUID categoryId, String title, String description,
                        Money startingPrice, Money reservePrice, Money minIncrement,
                        Money currentPrice, UUID currentWinnerId, AuctionStatus status,
                        Instant startTime, Instant endTime, int antiSnipeSeconds, long version) {
        this.id = id;
        this.sellerId = sellerId;
        this.categoryId = categoryId;
        this.title = title;
        this.description = description;
        this.startingPrice = startingPrice;
        this.reservePrice = reservePrice;
        this.minIncrement = minIncrement;
        this.currentPrice = currentPrice;
        this.currentWinnerId = currentWinnerId;
        this.status = status;
        this.startTime = startTime;
        this.endTime = endTime;
        this.antiSnipeSeconds = antiSnipeSeconds;
        this.version = version;
    }

    /**
     * Core invariant-enforcing logic (PDR §7.4). Executed inside the single-writer processor
     * (or, in the Phase-1 synchronous path, inside one DB transaction guarded by {@code version}).
     */
    public BidOutcome placeBid(UUID bidderId, Money amount, Instant now) {
        if (status != AuctionStatus.OPEN && status != AuctionStatus.EXTENDED) {
            return BidOutcome.rejected(BidRejectReason.AUCTION_NOT_OPEN);
        }
        if (!now.isBefore(endTime)) {
            return BidOutcome.rejected(BidRejectReason.AUCTION_ENDED);
        }
        if (bidderId.equals(sellerId)) {
            return BidOutcome.rejected(BidRejectReason.SELF_BID);
        }
        if (bidderId.equals(currentWinnerId)) {
            return BidOutcome.rejected(BidRejectReason.ALREADY_HIGHEST);
        }
        Money minAcceptable = currentPrice.plus(minIncrement);
        if (amount.isLessThan(minAcceptable)) {
            return BidOutcome.rejected(BidRejectReason.BELOW_MIN_INCREMENT);
        }

        UUID previousWinner = currentWinnerId;
        currentPrice = amount;
        currentWinnerId = bidderId;
        version++;

        boolean extended = maybeExtendForSniping(now);

        return BidOutcome.accepted(previousWinner, currentWinnerId, currentPrice, extended, endTime);
    }

    /**
     * If a bid lands within {@code antiSnipeSeconds} of {@code endTime}, push the end time out
     * (PDR §11.2). Applied inside the same writer that decided the bid, so it is race-free.
     */
    private boolean maybeExtendForSniping(Instant now) {
        Instant snipeWindowStart = endTime.minus(Duration.ofSeconds(antiSnipeSeconds));
        if (now.isBefore(snipeWindowStart)) {
            return false;
        }
        endTime = now.plusSeconds(antiSnipeSeconds);
        status = AuctionStatus.EXTENDED;
        return true;
    }

    /**
     * Closes the auction, deciding SOLD vs UNSOLD against the reserve (PDR §11.3). The caller
     * (auction-processor consuming a CLOSE command, or the scheduler in the synchronous path)
     * is responsible for ensuring this is only invoked once ordering guarantees the auction is
     * actually due; this method still defends against a stale/duplicate close.
     */
    public CloseOutcome close(Instant scheduledEndTime) {
        if (status == AuctionStatus.SOLD || status == AuctionStatus.UNSOLD
                || status == AuctionStatus.CANCELLED) {
            return new CloseOutcome.Ignored("auction already terminal: " + status);
        }
        if (endTime.isAfter(scheduledEndTime)) {
            // A bid extended the auction after this CLOSE was enqueued — stale trigger.
            return new CloseOutcome.Ignored("auction extended past scheduled close");
        }

        boolean reserveMet = reservePrice == null || currentPrice.isGreaterThanOrEqualTo(reservePrice);
        if (currentWinnerId != null && reserveMet) {
            status = AuctionStatus.CLOSING;
            AuctionStatus finalStatus = AuctionStatus.SOLD;
            this.status = finalStatus;
            return new CloseOutcome.Sold(currentWinnerId, currentPrice);
        }

        status = AuctionStatus.UNSOLD;
        return new CloseOutcome.Unsold();
    }

    public UUID id() {
        return id;
    }

    public UUID sellerId() {
        return sellerId;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public Money startingPrice() {
        return startingPrice;
    }

    public Money reservePrice() {
        return reservePrice;
    }

    public Money minIncrement() {
        return minIncrement;
    }

    public Money currentPrice() {
        return currentPrice;
    }

    public UUID currentWinnerId() {
        return currentWinnerId;
    }

    public AuctionStatus status() {
        return status;
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }

    public int antiSnipeSeconds() {
        return antiSnipeSeconds;
    }

    public long version() {
        return version;
    }
}
