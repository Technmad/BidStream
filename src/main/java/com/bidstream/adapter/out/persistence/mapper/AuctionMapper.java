package com.bidstream.adapter.out.persistence.mapper;

import com.bidstream.adapter.out.persistence.entity.AuctionJpaEntity;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AuctionStatus;
import com.bidstream.domain.model.Money;
import java.util.Currency;

public final class AuctionMapper {

    private AuctionMapper() {
    }

    public static AuctionItem toDomain(AuctionJpaEntity entity) {
        Currency currency = Currency.getInstance(entity.getCurrency());
        return new AuctionItem(
                entity.getId(), entity.getSellerId(), entity.getCategoryId(), entity.getTitle(),
                entity.getDescription(),
                Money.of(entity.getStartingPrice(), currency),
                entity.getReservePrice() == null ? null : Money.of(entity.getReservePrice(), currency),
                Money.of(entity.getMinIncrement(), currency),
                Money.of(entity.getCurrentPrice(), currency),
                entity.getCurrentWinnerId(),
                AuctionStatus.valueOf(entity.getStatus()),
                entity.getStartTime(), entity.getEndTime(), entity.getAntiSnipeSeconds(),
                entity.getVersion());
    }

    public static AuctionJpaEntity toEntity(AuctionItem auction, java.time.Instant createdAt) {
        return new AuctionJpaEntity(
                auction.id(), auction.sellerId(), auction.categoryId(), auction.title(),
                auction.description(),
                auction.startingPrice().amount(),
                auction.reservePrice() == null ? null : auction.reservePrice().amount(),
                auction.minIncrement().amount(),
                auction.currentPrice().amount(),
                auction.currentWinnerId(),
                auction.currentPrice().currency().getCurrencyCode(),
                auction.status().name(),
                auction.startTime(), auction.endTime(), auction.antiSnipeSeconds(),
                auction.version(), createdAt);
    }
}
