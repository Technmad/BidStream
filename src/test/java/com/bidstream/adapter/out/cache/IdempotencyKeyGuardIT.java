package com.bidstream.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Runs against the local dev stack's real Redis (docker/docker-compose.yml). */
@SpringBootTest
class IdempotencyKeyGuardIT {

    @Autowired
    private IdempotencyKeyGuard guard;

    @Test
    void firstUseIsTrueOnceThenFalseForTheSameKey() {
        UUID auctionId = UUID.randomUUID();
        UUID bidderId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        assertThat(guard.firstUse(auctionId, bidderId, key)).isTrue();
        assertThat(guard.firstUse(auctionId, bidderId, key)).isFalse();
        assertThat(guard.firstUse(auctionId, bidderId, key)).isFalse();
    }

    @Test
    void differentBiddersWithTheSameKeyDoNotCollide() {
        UUID auctionId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        assertThat(guard.firstUse(auctionId, UUID.randomUUID(), key)).isTrue();
        assertThat(guard.firstUse(auctionId, UUID.randomUUID(), key)).isTrue();
    }
}
