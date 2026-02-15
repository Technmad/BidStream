package com.bidstream.adapter.in.rest.dto;

import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AuctionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AuctionDtos {

    private AuctionDtos() {
    }

    public record CreateAuctionRequest(
            @NotBlank String title,
            String description,
            UUID categoryId,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal startingPrice,
            BigDecimal reservePrice,
            @NotNull @DecimalMin(value = "0.01") BigDecimal minIncrement,
            @NotNull Instant startTime,
            @NotNull @Future Instant endTime,
            Integer antiSnipeSeconds) {
    }

    public record UpdateAuctionRequest(String title, String description, Instant startTime,
                                        Instant endTime) {
    }

    public record AuctionResponse(
            UUID id, UUID sellerId, UUID categoryId, String title, String description,
            BigDecimal startingPrice, BigDecimal reservePrice, BigDecimal minIncrement,
            BigDecimal currentPrice, UUID currentWinnerId, AuctionStatus status,
            Instant startTime, Instant endTime, int antiSnipeSeconds, long version) {

        public static AuctionResponse from(AuctionItem a) {
            return new AuctionResponse(
                    a.id(), a.sellerId(), a.categoryId(), a.title(), a.description(),
                    a.startingPrice().amount(),
                    a.reservePrice() == null ? null : a.reservePrice().amount(),
                    a.minIncrement().amount(), a.currentPrice().amount(), a.currentWinnerId(),
                    a.status(), a.startTime(), a.endTime(), a.antiSnipeSeconds(), a.version());
        }
    }
}
