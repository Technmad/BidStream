package com.bidstream.adapter.out.persistence.mapper;

import com.bidstream.adapter.out.persistence.entity.BidJpaEntity;
import com.bidstream.domain.model.Bid;
import com.bidstream.domain.model.BidStatus;
import com.bidstream.domain.model.BidType;
import com.bidstream.domain.model.Money;
import java.util.Currency;

public final class BidMapper {

    private BidMapper() {
    }

    public static Bid toDomain(BidJpaEntity entity) {
        return new Bid(entity.getId(), entity.getAuctionId(), entity.getBidderId(),
                Money.of(entity.getAmount(), Currency.getInstance("USD")),
                BidType.valueOf(entity.getType()), BidStatus.valueOf(entity.getStatus()),
                entity.getIdempotencyKey(), entity.getCreatedAt());
    }

    public static BidJpaEntity toEntity(Bid bid) {
        return new BidJpaEntity(bid.id(), bid.auctionId(), bid.bidderId(), bid.amount().amount(),
                bid.type().name(), bid.status().name(), bid.idempotencyKey(), bid.createdAt());
    }
}
