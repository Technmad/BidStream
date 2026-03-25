package com.bidstream.adapter.in.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AutoBidDtos {

    private AutoBidDtos() {
    }

    public record SetAutoBidRequest(@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxAmount) {
    }

    public record AutoBidResponse(UUID id, UUID auctionId, UUID bidderId, BigDecimal maxAmount,
                                   boolean active, Instant createdAt) {

        public static AutoBidResponse from(com.bidstream.domain.model.AutoBid autoBid) {
            return new AutoBidResponse(autoBid.id(), autoBid.auctionId(), autoBid.bidderId(),
                    autoBid.maxAmount().amount(), autoBid.active(), autoBid.createdAt());
        }
    }
}
