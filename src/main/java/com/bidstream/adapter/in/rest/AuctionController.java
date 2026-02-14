package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.AuctionDtos.AuctionResponse;
import com.bidstream.adapter.in.rest.dto.AuctionDtos.CreateAuctionRequest;
import com.bidstream.adapter.in.rest.dto.AuctionDtos.UpdateAuctionRequest;
import com.bidstream.application.AuctionService;
import com.bidstream.common.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.bidstream.domain.model.AuctionStatus;
import com.bidstream.domain.model.Money;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auctions")
public class AuctionController {

    private static final Currency USD = Currency.getInstance("USD");

    private final AuctionService auctionService;

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @GetMapping
    public Page<AuctionResponse> list(
            @RequestParam(required = false) AuctionStatus status,
            @RequestParam(required = false) UUID category,
            Pageable pageable) {
        return auctionService.search(status, category, pageable).map(AuctionResponse::from);
    }

    @GetMapping("/{id}")
    public AuctionResponse get(@PathVariable UUID id) {
        return AuctionResponse.from(auctionService.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuctionResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                   @Valid @RequestBody CreateAuctionRequest request) {
        var auction = auctionService.create(
                user.id(), request.categoryId(), request.title(), request.description(),
                Money.of(request.startingPrice(), USD),
                request.reservePrice() == null ? null : Money.of(request.reservePrice(), USD),
                Money.of(request.minIncrement(), USD),
                request.startTime(), request.endTime(),
                request.antiSnipeSeconds() == null ? 30 : request.antiSnipeSeconds());
        return AuctionResponse.from(auction);
    }

    @PatchMapping("/{id}")
    public AuctionResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable UUID id,
                                   @RequestBody UpdateAuctionRequest request) {
        var auction = auctionService.update(id, user.id(), request.title(), request.description(),
                request.startTime(), request.endTime());
        return AuctionResponse.from(auction);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser user,
                                        @PathVariable UUID id) {
        boolean isAdmin = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        auctionService.cancel(id, user.id(), isAdmin);
        return ResponseEntity.noContent().build();
    }
}
