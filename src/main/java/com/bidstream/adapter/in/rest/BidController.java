package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.BidDtos.BidAcceptedResponse;
import com.bidstream.adapter.in.rest.dto.BidDtos.BidHistoryEntry;
import com.bidstream.adapter.in.rest.dto.BidDtos.PlaceBidRequest;
import com.bidstream.application.BidHistoryService;
import com.bidstream.application.PlaceBidUseCase;
import com.bidstream.application.PlaceBidUseCase.PlaceBidResult;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.model.Money;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase-1 synchronous bid endpoint (PDR §14.2 contract, minus the async 202/WebSocket split
 * that Phase 2 introduces once bids flow through Kafka). The response here is the final,
 * synchronous decision rather than a queued acknowledgement.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
public class BidController {

    private static final Currency USD = Currency.getInstance("USD");

    private final PlaceBidUseCase placeBidUseCase;
    private final BidHistoryService bidHistoryService;

    public BidController(PlaceBidUseCase placeBidUseCase, BidHistoryService bidHistoryService) {
        this.placeBidUseCase = placeBidUseCase;
        this.bidHistoryService = bidHistoryService;
    }

    @GetMapping
    public Page<BidHistoryEntry> history(@PathVariable UUID auctionId, Pageable pageable) {
        return bidHistoryService.forAuction(auctionId, pageable).map(BidHistoryEntry::from);
    }

    @PostMapping
    public ResponseEntity<?> placeBid(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID auctionId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody PlaceBidRequest request) {
        PlaceBidResult result = placeBidUseCase.execute(
                auctionId, user.id(), Money.of(request.amount(), USD), idempotencyKey);

        if (result.outcome() instanceof BidOutcome.Rejected rejected) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    rejected.reason().name());
            problem.setProperty("reason", rejected.reason().name());
            problem.setProperty("currentPrice", result.currentPrice().amount());
            problem.setProperty("minIncrement", result.minIncrement().amount());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        BidOutcome.Accepted accepted = (BidOutcome.Accepted) result.outcome();
        return ResponseEntity.ok(new BidAcceptedResponse(
                result.bidId(), "ACCEPTED", accepted.previousWinnerId(),
                accepted.newPrice().amount(), accepted.extended(), accepted.newEndTime()));
    }
}
