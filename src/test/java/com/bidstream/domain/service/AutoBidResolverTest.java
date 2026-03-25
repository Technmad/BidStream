package com.bidstream.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidstream.domain.model.Money;
import com.bidstream.domain.service.AutoBidResolver.Challenger;
import com.bidstream.domain.service.AutoBidResolver.Leader;
import com.bidstream.domain.service.AutoBidResolver.Resolution;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises the exact worked example from PDR §12.2. */
class AutoBidResolverTest {

    private static final Money INCREMENT = Money.of("5.00", "USD");
    private static final Instant T0 = Instant.parse("2026-03-25T12:00:00Z");

    private static Money money(String amount) {
        return Money.of(amount, "USD");
    }

    @Test
    void worksThroughThePdrWorkedExample() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();

        // Alice sets auto-bid max = $100 -> price = $50 (starting), Alice winning.
        Resolution step1 = AutoBidResolver.resolve(money("50.00"), INCREMENT, null,
                new Challenger(alice, money("100.00"), T0));
        assertThat(step1.winnerId()).isEqualTo(alice);
        assertThat(step1.price()).isEqualTo(money("50.00"));

        // Bob sets auto-bid max = $80. Bob(80) <= Alice.max(100) -> Alice retains lead.
        // price = min(100, 80 + 5) = $85, Alice winning. Bob outbid.
        Resolution step2 = AutoBidResolver.resolve(step1.price(), INCREMENT,
                new Leader(alice, money("100.00"), T0),
                new Challenger(bob, money("80.00"), T0.plusSeconds(60)));
        assertThat(step2.winnerId()).isEqualTo(alice);
        assertThat(step2.price()).isEqualTo(money("85.00"));
        assertThat(step2.leaderChanged()).isFalse();

        // Carol bids manual $120. Carol(120) > Alice.max(100) -> Carol takes lead.
        // price = min(120, 100 + 5) = $105, Carol winning. Alice outbid.
        Resolution step3 = AutoBidResolver.resolve(step2.price(), INCREMENT,
                new Leader(alice, money("100.00"), T0),
                new Challenger(carol, money("120.00"), T0.plusSeconds(120)));
        assertThat(step3.winnerId()).isEqualTo(carol);
        assertThat(step3.price()).isEqualTo(money("105.00"));
        assertThat(step3.leaderChanged()).isTrue();
    }

    @Test
    void tieOnMaxKeepsTheEarlierLeader() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        Resolution resolution = AutoBidResolver.resolve(money("50.00"), INCREMENT,
                new Leader(alice, money("100.00"), T0),
                new Challenger(bob, money("100.00"), T0.plusSeconds(60)));

        assertThat(resolution.winnerId()).isEqualTo(alice);
        assertThat(resolution.leaderChanged()).isFalse();
    }

    @Test
    void aSingleManualBidWithNoLeaderClaimsTheCurrentPrice() {
        UUID bidder = UUID.randomUUID();

        Resolution resolution = AutoBidResolver.resolve(money("50.00"), INCREMENT, null,
                new Challenger(bidder, money("55.00"), T0));

        assertThat(resolution.winnerId()).isEqualTo(bidder);
        assertThat(resolution.price()).isEqualTo(money("50.00"));
    }
}
