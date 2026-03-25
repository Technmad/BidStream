package com.bidstream.adapter.out.persistence.impl;

import com.bidstream.adapter.out.persistence.entity.AutoBidJpaEntity;
import com.bidstream.adapter.out.persistence.mapper.AutoBidMapper;
import com.bidstream.adapter.out.persistence.repository.AutoBidJpaRepository;
import com.bidstream.domain.model.AutoBid;
import com.bidstream.domain.port.AutoBidRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class AutoBidRepositoryImpl implements AutoBidRepository {

    private final AutoBidJpaRepository jpaRepository;

    public AutoBidRepositoryImpl(AutoBidJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AutoBid save(AutoBid autoBid) {
        var saved = jpaRepository.save(AutoBidMapper.toEntity(autoBid));
        return AutoBidMapper.toDomain(saved);
    }

    @Override
    public Optional<AutoBid> findByAuctionIdAndBidderId(UUID auctionId, UUID bidderId) {
        return jpaRepository.findByAuctionIdAndBidderId(auctionId, bidderId).map(AutoBidMapper::toDomain);
    }

    @Override
    public List<AutoBid> findActiveByAuctionId(UUID auctionId) {
        return jpaRepository.findByAuctionIdAndActiveTrueOrderByCreatedAtAsc(auctionId).stream()
                .map(AutoBidMapper::toDomain)
                .toList();
    }

    @Override
    public void deactivate(UUID auctionId, UUID bidderId) {
        jpaRepository.findByAuctionIdAndBidderId(auctionId, bidderId).ifPresent(entity -> {
            AutoBidJpaEntity deactivated = new AutoBidJpaEntity(entity.getId(), entity.getAuctionId(),
                    entity.getBidderId(), entity.getMaxAmount(), false, entity.getCreatedAt());
            jpaRepository.save(deactivated);
        });
    }
}
