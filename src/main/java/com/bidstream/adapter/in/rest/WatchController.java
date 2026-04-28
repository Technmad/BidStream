package com.bidstream.adapter.in.rest;

import com.bidstream.application.WatchService;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PDR §14.4: a durable bookmark, not a live-delivery subscription (§15.1) - see GET /me/watching. */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/watch")
@Tag(name = "Watch", description = "Persist/remove an auction from the caller's watchlist")
public class WatchController {

    private final WatchService watchService;

    public WatchController(WatchService watchService) {
        this.watchService = watchService;
    }

    @PostMapping
    @Operation(summary = "Start watching an auction",
            description = "Idempotent - watching twice is a no-op, not a duplicate or an error.")
    public ResponseEntity<Void> watch(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID auctionId) {
        watchService.watch(auctionId, user.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Stop watching an auction",
            description = "Idempotent - unwatching something never watched is a no-op, not a 404.")
    public ResponseEntity<Void> unwatch(@AuthenticationPrincipal AuthenticatedUser user,
                                         @PathVariable UUID auctionId) {
        watchService.unwatch(auctionId, user.id());
        return ResponseEntity.noContent().build();
    }
}
