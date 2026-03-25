package com.bidstream.domain.service;

import com.bidstream.domain.model.Money;
import java.time.Instant;
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
     */
    public static Resolution resolve(Money currentPrice, Money minIncrement, Leader leader,
                                      Challenger challenger) {
        if (leader == null) {
            // No competition yet: the challenger simply claims the current price - never
            // reveals more than that, whatever their own max might be.
            return new Resolution(challenger.bidderId(), currentPrice, true);
        }

        // Exact tie on max: earliest-submitted auto-bid wins, i.e. the leader keeps it.
        boolean challengerTakesLead = challenger.max().isGreaterThan(leader.max());

        if (!challengerTakesLead) {
            Money price = minOf(leader.max(), challenger.max().plus(minIncrement));
            return new Resolution(leader.bidderId(), price, false);
        }

        Money price = minOf(challenger.max(), leader.max().plus(minIncrement));
        return new Resolution(challenger.bidderId(), price, true);
    }

    private static Money minOf(Money a, Money b) {
        return a.isLessThan(b) ? a : b;
    }
}
