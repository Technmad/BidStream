package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.AutoBidDtos.AutoBidResponse;
import com.bidstream.adapter.in.rest.dto.AutoBidDtos.SetAutoBidRequest;
import com.bidstream.application.AutoBidService;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.bidstream.domain.model.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/auto-bid")
@Tag(name = "Auto-Bid", description = "Set or cancel proxy (auto-)bidding for an auction")
public class AutoBidController {

    private static final Currency USD = Currency.getInstance("USD");

    private final AutoBidService autoBidService;

    public AutoBidController(AutoBidService autoBidService) {
        this.autoBidService = autoBidService;
    }

    @PostMapping
    @Operation(summary = "Set (or replace) an auto-bid", description = "The system bids on the "
            + "user's behalf up to maxAmount as competing bids come in.")
    public AutoBidResponse set(@AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable UUID auctionId,
                                @Valid @RequestBody SetAutoBidRequest request) {
        var autoBid = autoBidService.setAutoBid(auctionId, user.id(), Money.of(request.maxAmount(), USD));
        return AutoBidResponse.from(autoBid);
    }

    @DeleteMapping
    @Operation(summary = "Cancel the caller's auto-bid for this auction")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                        @PathVariable UUID auctionId) {
        autoBidService.cancelAutoBid(auctionId, user.id());
        return ResponseEntity.noContent().build();
    }
}
