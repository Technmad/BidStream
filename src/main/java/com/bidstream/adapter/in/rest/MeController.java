package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.AuctionDtos.AuctionResponse;
import com.bidstream.adapter.in.rest.dto.BidDtos.BidHistoryEntry;
import com.bidstream.application.AuctionService;
import com.bidstream.application.BidHistoryService;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The authenticated caller's own bidding activity")
public class MeController {

    private final BidHistoryService bidHistoryService;
    private final AuctionService auctionService;

    public MeController(BidHistoryService bidHistoryService, AuctionService auctionService) {
        this.bidHistoryService = bidHistoryService;
        this.auctionService = auctionService;
    }

    @GetMapping("/bids")
    @Operation(summary = "List the caller's own bid history across all auctions")
    public List<BidHistoryEntry> myBids(@AuthenticationPrincipal AuthenticatedUser user,
                                         Pageable pageable) {
        return bidHistoryService.forBidder(user.id(), pageable).stream()
                .map(BidHistoryEntry::from)
                .toList();
    }

    @GetMapping("/watching")
    @Operation(summary = "List the caller's watched auctions",
            description = "Durable bookmark list (PDR §14.4), all statuses, ordered by end time - "
                    + "independent of any live WebSocket subscription.")
    public Page<AuctionResponse> watching(@AuthenticationPrincipal AuthenticatedUser user,
                                           Pageable pageable) {
        return auctionService.watchedByUser(user.id(), pageable).map(AuctionResponse::from);
    }
}
