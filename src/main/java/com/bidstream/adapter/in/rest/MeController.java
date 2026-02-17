package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.BidDtos.BidHistoryEntry;
import com.bidstream.application.BidHistoryService;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final BidHistoryService bidHistoryService;

    public MeController(BidHistoryService bidHistoryService) {
        this.bidHistoryService = bidHistoryService;
    }

    @GetMapping("/bids")
    public List<BidHistoryEntry> myBids(@AuthenticationPrincipal AuthenticatedUser user,
                                         Pageable pageable) {
        return bidHistoryService.forBidder(user.id(), pageable).stream()
                .map(BidHistoryEntry::from)
                .toList();
    }
}
