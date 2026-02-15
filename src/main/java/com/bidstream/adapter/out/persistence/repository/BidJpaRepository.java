package com.bidstream.adapter.out.persistence.repository;

import com.bidstream.adapter.out.persistence.entity.BidJpaEntity;
import com.bidstream.adapter.out.persistence.entity.BidJpaEntity.BidId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BidJpaRepository extends JpaRepository<BidJpaEntity, BidId> {

    Page<BidJpaEntity> findByAuctionIdOrderByCreatedAtDesc(UUID auctionId, Pageable pageable);

    List<BidJpaEntity> findByBidderIdOrderByCreatedAtDesc(UUID bidderId, Pageable pageable);

    boolean existsByAuctionIdAndBidderIdAndIdempotencyKey(UUID auctionId, UUID bidderId,
                                                           String idempotencyKey);
}
