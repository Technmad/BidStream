package com.bidstream.adapter.out.persistence.mapper;

import com.bidstream.adapter.out.persistence.entity.AutoBidJpaEntity;
import com.bidstream.domain.model.AutoBid;
import com.bidstream.domain.model.Money;
import java.util.Currency;

public final class AutoBidMapper {

    private AutoBidMapper() {
    }

    public static AutoBid toDomain(AutoBidJpaEntity entity) {
        return new AutoBid(entity.getId(), entity.getAuctionId(), entity.getBidderId(),
                Money.of(entity.getMaxAmount(), Currency.getInstance("USD")), entity.isActive(),
                entity.getCreatedAt());
    }

    public static AutoBidJpaEntity toEntity(AutoBid autoBid) {
        return new AutoBidJpaEntity(autoBid.id(), autoBid.auctionId(), autoBid.bidderId(),
                autoBid.maxAmount().amount(), autoBid.active(), autoBid.createdAt());
    }
}
