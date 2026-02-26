package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.BidDtos.BidAcceptedResponse;
import com.bidstream.adapter.in.rest.dto.BidDtos.BidHistoryEntry;
import com.bidstream.adapter.in.rest.dto.BidDtos.BidPendingResponse;
import com.bidstream.adapter.in.rest.dto.BidDtos.PlaceBidRequest;
import com.bidstream.application.BidDecisionWaiter;
import com.bidstream.application.BidHistoryService;
import com.bidstream.application.SubmitBidCommandUseCase;
import com.bidstream.common.ConflictException;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.bidstream.domain.model.BidOutcome;
import com.bidstream.domain.port.BidRepository;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * The bid endpoint (PDR §14.2): publishes onto {@code auction.commands} and returns
 * synchronously if the single-writer processor decides within a short window, otherwise falls
 * back to {@code 202 Accepted} - the authoritative outcome always arrives over WebSocket (Phase
 * 3) regardless of which path this call takes.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
public class BidController {

    private static final Duration SYNC_WAIT_TIMEOUT = Duration.ofSeconds(5);

    private final SubmitBidCommandUseCase submitBidCommandUseCase;
    private final BidDecisionWaiter decisionWaiter;
    private final BidRepository bidRepository;
    private final BidHistoryService bidHistoryService;

    public BidController(SubmitBidCommandUseCase submitBidCommandUseCase,
                          BidDecisionWaiter decisionWaiter, BidRepository bidRepository,
                          BidHistoryService bidHistoryService) {
        this.submitBidCommandUseCase = submitBidCommandUseCase;
        this.decisionWaiter = decisionWaiter;
        this.bidRepository = bidRepository;
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
        if (bidRepository.existsByIdempotencyKey(auctionId, user.id(), idempotencyKey)) {
            throw new ConflictException("Duplicate bid: idempotency key already used");
        }

        var command = submitBidCommandUseCase.submit(
                auctionId, user.id(), request.amount(), "USD", idempotencyKey);
        var waitFuture = decisionWaiter.register(command.eventId());

        try {
            BidDecisionWaiter.Decision decision =
                    waitFuture.get(SYNC_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return respond(decision);
        } catch (TimeoutException e) {
            return ResponseEntity.accepted().body(new BidPendingResponse(
                    command.eventId(), "PENDING", command.correlationId()));
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.accepted().body(new BidPendingResponse(
                    command.eventId(), "PENDING", command.correlationId()));
        }
    }

    private ResponseEntity<?> respond(BidDecisionWaiter.Decision decision) {
        if (decision.outcome() instanceof BidOutcome.Rejected rejected) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    rejected.reason().name());
            problem.setProperty("reason", rejected.reason().name());
            problem.setProperty("currentPrice", decision.currentPrice());
            problem.setProperty("minIncrement", decision.minIncrement());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        BidOutcome.Accepted accepted = (BidOutcome.Accepted) decision.outcome();
        return ResponseEntity.ok(new BidAcceptedResponse(
                decision.bidId(), "ACCEPTED", accepted.previousWinnerId(),
                accepted.newPrice().amount(), accepted.extended(), accepted.newEndTime()));
    }
}
