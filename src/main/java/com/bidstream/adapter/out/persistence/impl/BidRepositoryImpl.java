package com.bidstream.adapter.out.persistence.impl;

import com.bidstream.adapter.out.persistence.mapper.BidMapper;
import com.bidstream.adapter.out.persistence.repository.BidJpaRepository;
import com.bidstream.domain.model.Bid;
import com.bidstream.domain.port.BidRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
public class BidRepositoryImpl implements BidRepository {

    private final BidJpaRepository jpaRepository;

    public BidRepositoryImpl(BidJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Bid save(Bid bid) {
        var saved = jpaRepository.save(BidMapper.toEntity(bid));
        return BidMapper.toDomain(saved);
    }

    @Override
    public Page<Bid> findByAuctionId(UUID auctionId, Pageable pageable) {
        return jpaRepository.findByAuctionIdOrderByCreatedAtDesc(auctionId, pageable)
                .map(BidMapper::toDomain);
    }

    @Override
    public List<Bid> findByBidderId(UUID bidderId, Pageable pageable) {
        return jpaRepository.findByBidderIdOrderByCreatedAtDesc(bidderId, pageable).stream()
                .map(BidMapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsByIdempotencyKey(UUID auctionId, UUID bidderId, String idempotencyKey) {
        return jpaRepository.existsByAuctionIdAndBidderIdAndIdempotencyKey(
                auctionId, bidderId, idempotencyKey);
    }
}
