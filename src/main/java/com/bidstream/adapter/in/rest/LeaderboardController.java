package com.bidstream.adapter.in.rest;

import com.bidstream.domain.port.LeaderboardCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Top-bidders display (PDR §13) - read straight from the rebuildable Redis projection. */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/leaderboard")
@Tag(name = "Leaderboard", description = "Top-bidders projection for an auction")
public class LeaderboardController {

    private final LeaderboardCache leaderboardCache;

    public LeaderboardController(LeaderboardCache leaderboardCache) {
        this.leaderboardCache = leaderboardCache;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(summary = "Get the top-N current bidders for an auction")
    public List<Entry> top(@PathVariable UUID auctionId,
                            @RequestParam(defaultValue = "10") int limit) {
        return leaderboardCache.topN(auctionId, limit).stream()
                .map(e -> new Entry(e.bidderId(), e.amount()))
                .toList();
    }

    public record Entry(UUID bidderId, BigDecimal amount) {
    }
}
