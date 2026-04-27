package com.bidstream.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuctionItemTest {

    private static final Instant NOW = Instant.parse("2026-02-05T12:00:00Z");

    private UUID sellerId;
    private UUID bidderA;
    private UUID bidderB;
    private AuctionItem auction;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        bidderA = UUID.randomUUID();
        bidderB = UUID.randomUUID();
        auction = openAuction(NOW.plusSeconds(300));
    }

    private AuctionItem openAuction(Instant endTime) {
        return new AuctionItem(
                UUID.randomUUID(), sellerId, UUID.randomUUID(), "Vintage Watch", "desc",
                Money.of("50.00", "USD"), Money.of("100.00", "USD"), Money.of("5.00", "USD"),
                Money.of("50.00", "USD"), null, AuctionStatus.OPEN,
                NOW.minusSeconds(60), endTime, 30, 0L);
    }

    @Test
    void firstValidBidIsAccepted() {
        BidOutcome outcome = auction.placeBid(bidderA, Money.of("55.00", "USD"), NOW);

        assertThat(outcome).isInstanceOf(BidOutcome.Accepted.class);
        BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
        assertThat(accepted.previousWinnerId()).isNull();
        assertThat(accepted.newWinnerId()).isEqualTo(bidderA);
        assertThat(auction.currentPrice()).isEqualTo(Money.of("55.00", "USD"));
        assertThat(auction.version()).isEqualTo(1L);
    }

    @Test
    void bidBelowMinIncrementIsRejected() {
        BidOutcome outcome = auction.placeBid(bidderA, Money.of("52.00", "USD"), NOW);

        assertThat(outcome).isEqualTo(BidOutcome.rejected(BidRejectReason.BELOW_MIN_INCREMENT));
    }

    @Test
    void sellerCannotBidOnOwnAuction() {
        BidOutcome outcome = auction.placeBid(sellerId, Money.of("60.00", "USD"), NOW);

        assertThat(outcome).isEqualTo(BidOutcome.rejected(BidRejectReason.SELF_BID));
    }

    @Test
    void currentHighestBidderCannotBidAgain() {
        auction.placeBid(bidderA, Money.of("55.00", "USD"), NOW);

        BidOutcome outcome = auction.placeBid(bidderA, Money.of("60.00", "USD"), NOW);

        assertThat(outcome).isEqualTo(BidOutcome.rejected(BidRejectReason.ALREADY_HIGHEST));
    }

    @Test
    void bidAfterEndTimeIsRejectedAsAuctionEnded() {
        Instant past = NOW.plusSeconds(301);

        BidOutcome outcome = auction.placeBid(bidderA, Money.of("55.00", "USD"), past);

        assertThat(outcome).isEqualTo(BidOutcome.rejected(BidRejectReason.AUCTION_ENDED));
    }

    @Test
    void bidNotOnOpenOrExtendedAuctionIsRejected() {
        AuctionItem scheduled = new AuctionItem(
                UUID.randomUUID(), sellerId, UUID.randomUUID(), "Not yet open", "desc",
                Money.of("50.00", "USD"), null, Money.of("5.00", "USD"),
                Money.of("50.00", "USD"), null, AuctionStatus.SCHEDULED,
                NOW.plusSeconds(60), NOW.plusSeconds(360), 30, 0L);

        BidOutcome outcome = scheduled.placeBid(bidderA, Money.of("55.00", "USD"), NOW);

        assertThat(outcome).isEqualTo(BidOutcome.rejected(BidRejectReason.AUCTION_NOT_OPEN));
    }

    @Test
    void bidWithinAntiSnipeWindowExtendsEndTimeAndSwitchesToExtended() {
        AuctionItem endingSoon = openAuction(NOW.plusSeconds(10));

        BidOutcome outcome = endingSoon.placeBid(bidderA, Money.of("55.00", "USD"), NOW);

        BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
        assertThat(accepted.extended()).isTrue();
        assertThat(endingSoon.status()).isEqualTo(AuctionStatus.EXTENDED);
        assertThat(endingSoon.endTime()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void bidExactlyAtEndTimeIsRejectedAsAuctionEnded() {
        BidOutcome outcome = auction.placeBid(bidderA, Money.of("55.00", "USD"), auction.endTime());

        assertThat(outcome).isEqualTo(BidOutcome.rejected(BidRejectReason.AUCTION_ENDED));
    }

    @Test
    void bidExactlyAtTheAntiSnipeWindowBoundaryIsANoOp() {
        // antiSnipeSeconds is 30 (openAuction's default) and endTime is NOW+30, so
        // snipeWindowStart == NOW exactly - the formula's new end time equals the existing one.
        AuctionItem endingSoon = openAuction(NOW.plusSeconds(30));

        BidOutcome outcome = endingSoon.placeBid(bidderA, Money.of("55.00", "USD"), NOW);

        BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
        assertThat(accepted.extended()).isFalse();
        assertThat(endingSoon.status()).isEqualTo(AuctionStatus.OPEN);
        assertThat(endingSoon.endTime()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void bidOneInstantPastTheAntiSnipeBoundaryDoesExtend() {
        AuctionItem endingSoon = openAuction(NOW.plusSeconds(30));

        BidOutcome outcome = endingSoon.placeBid(bidderA, Money.of("55.00", "USD"), NOW.plusSeconds(1));

        BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
        assertThat(accepted.extended()).isTrue();
        assertThat(endingSoon.status()).isEqualTo(AuctionStatus.EXTENDED);
        assertThat(endingSoon.endTime()).isEqualTo(NOW.plusSeconds(31));
    }

    @Test
    void newHighestBidderOutbidsThePreviousOne() {
        auction.placeBid(bidderA, Money.of("55.00", "USD"), NOW);

        BidOutcome outcome = auction.placeBid(bidderB, Money.of("65.00", "USD"), NOW);

        BidOutcome.Accepted accepted = (BidOutcome.Accepted) outcome;
        assertThat(accepted.previousWinnerId()).isEqualTo(bidderA);
        assertThat(accepted.newWinnerId()).isEqualTo(bidderB);
        assertThat(auction.currentWinnerId()).isEqualTo(bidderB);
    }

    @Test
    void closeAboveReserveWithAWinnerIsSold() {
        auction.placeBid(bidderA, Money.of("120.00", "USD"), NOW);

        CloseOutcome outcome = auction.close(auction.endTime());

        assertThat(outcome).isInstanceOf(CloseOutcome.Sold.class);
        assertThat(((CloseOutcome.Sold) outcome).winnerId()).isEqualTo(bidderA);
        assertThat(auction.status()).isEqualTo(AuctionStatus.SOLD);
    }

    @Test
    void closeBelowReserveIsUnsold() {
        auction.placeBid(bidderA, Money.of("60.00", "USD"), NOW); // below the 100.00 reserve

        CloseOutcome outcome = auction.close(auction.endTime());

        assertThat(outcome).isInstanceOf(CloseOutcome.Unsold.class);
        assertThat(auction.status()).isEqualTo(AuctionStatus.UNSOLD);
    }

    @Test
    void closeWithNoBidsIsUnsold() {
        CloseOutcome outcome = auction.close(auction.endTime());

        assertThat(outcome).isInstanceOf(CloseOutcome.Unsold.class);
    }

    @Test
    void staleCloseAfterExtensionIsIgnored() {
        AuctionItem endingSoon = openAuction(NOW.plusSeconds(10));
        Instant originalScheduledEnd = endingSoon.endTime();
        endingSoon.placeBid(bidderA, Money.of("120.00", "USD"), NOW); // extends past reserve

        CloseOutcome outcome = endingSoon.close(originalScheduledEnd);

        assertThat(outcome).isInstanceOf(CloseOutcome.Ignored.class);
        assertThat(endingSoon.status()).isEqualTo(AuctionStatus.EXTENDED);
    }

    @Test
    void duplicateCloseOnAlreadyTerminalAuctionIsIgnored() {
        auction.placeBid(bidderA, Money.of("120.00", "USD"), NOW);
        auction.close(auction.endTime());

        CloseOutcome outcome = auction.close(auction.endTime());

        assertThat(outcome).isInstanceOf(CloseOutcome.Ignored.class);
    }
}
