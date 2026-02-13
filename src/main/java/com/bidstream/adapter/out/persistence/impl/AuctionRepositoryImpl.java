package com.bidstream.adapter.out.persistence.impl;

import com.bidstream.adapter.out.persistence.mapper.AuctionMapper;
import com.bidstream.adapter.out.persistence.repository.AuctionJpaRepository;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.port.AuctionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuctionRepositoryImpl implements AuctionRepository {

    private final AuctionJpaRepository jpaRepository;

    public AuctionRepositoryImpl(AuctionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public AuctionItem save(AuctionItem auction) {
        Instant createdAt = jpaRepository.findById(auction.id())
                .map(e -> e.getCreatedAt())
                .orElseGet(Instant::now);
        var saved = jpaRepository.save(AuctionMapper.toEntity(auction, createdAt));
        return AuctionMapper.toDomain(saved);
    }

    @Override
    public Optional<AuctionItem> findById(UUID id) {
        return jpaRepository.findById(id).map(AuctionMapper::toDomain);
    }

    @Override
    @Transactional
    public boolean saveWithOptimisticLock(AuctionItem auction, long expectedVersion) {
        int updated = jpaRepository.updateWithOptimisticLock(
                auction.id(),
                auction.currentPrice().amount(),
                auction.currentWinnerId(),
                auction.status().name(),
                auction.endTime(),
                expectedVersion);
        return updated == 1;
    }
}
