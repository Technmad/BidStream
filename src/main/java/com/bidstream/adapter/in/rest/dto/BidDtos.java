package com.bidstream.adapter.in.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class BidDtos {

    private BidDtos() {
    }

    public record PlaceBidRequest(@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
    }

    public record BidAcceptedResponse(UUID bidId, String status, UUID previousWinnerId,
                                       BigDecimal newPrice, boolean extended, Instant newEndTime) {
    }

    public record BidHistoryEntry(UUID id, UUID auctionId, UUID bidderId, BigDecimal amount,
                                   String type, String status, Instant createdAt) {

        public static BidHistoryEntry from(com.bidstream.domain.model.Bid bid) {
            return new BidHistoryEntry(bid.id(), bid.auctionId(), bid.bidderId(),
                    bid.amount().amount(), bid.type().name(), bid.status().name(),
                    bid.createdAt());
        }
    }
}
