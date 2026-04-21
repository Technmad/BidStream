package com.bidstream.domain.service;

import com.bidstream.domain.model.Money;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * eBay-style proxy bidding (PDR §12): a bidder sets a maximum, and the system keeps them
 * winning at the lowest price necessary until someone exceeds their max. Pure domain logic -
 * takes the current auto-bid leader (if any) and a challenger, returns the resolved ladder step.
 * A manual bid is modeled as a challenger whose "max" is simply the bid amount itself, so the
 * same three rules cover both manual bids and auto-bids uniformly (PDR §12.2's worked example).
 */
public final class AutoBidResolver {

    private AutoBidResolver() {
    }

    public record Leader(UUID bidderId, Money max, Instant createdAt) {
    }

    public record Challenger(UUID bidderId, Money max, Instant createdAt) {
    }

    public record Resolution(UUID winnerId, Money price, boolean leaderChanged) {
    }

    /**
     * @param currentPrice the auction's price before this challenger arrived
     * @param minIncrement the auction's minimum increment
     * @param leader       the current auto-bid leader, or {@code null} if none
     * @param challenger   the new manual bid or auto-bid being resolved against the leader
     * @return the resolved ladder step, or {@link Optional#empty()} when there is no leader and
     *         the challenger's own max doesn't even clear the same floor a manual bid must clear
     *         ({@code currentPrice + minIncrement}, per {@code AuctionItem.placeBid}) - their
     *         auto-bid is still recorded as a standing instruction by the caller, it just doesn't
     *         win anything yet.
     */
    public static Optional<Resolution> resolve(Money currentPrice, Money minIncrement, Leader leader,
                                                Challenger challenger) {
        if (leader == null) {
            // No competition yet, but the challenger's max still has to clear the same floor a
            // manual bid would - otherwise their stated maximum doesn't justify winning at all.
            Money floor = currentPrice.plus(minIncrement);
            if (challenger.max().isLessThan(floor)) {
                return Optional.empty();
            }
            // Never reveals more than the current price, whatever their own max might be.
            return Optional.of(new Resolution(challenger.bidderId(), currentPrice, true));
        }

        // Exact tie on max: earliest-submitted bid wins outright, regardless of which argument
        // position it was passed in as - not just "the leader keeps it by convention."
        boolean challengerTakesLead = challenger.max().isGreaterThan(leader.max())
                || (challenger.max().equals(leader.max()) && challenger.createdAt().isBefore(leader.createdAt()));

        if (!challengerTakesLead) {
            Money price = minOf(leader.max(), challenger.max().plus(minIncrement));
            return Optional.of(new Resolution(leader.bidderId(), price, false));
        }

        Money price = minOf(challenger.max(), leader.max().plus(minIncrement));
        return Optional.of(new Resolution(challenger.bidderId(), price, true));
    }

    private static Money minOf(Money a, Money b) {
        return a.isLessThan(b) ? a : b;
    }
}
