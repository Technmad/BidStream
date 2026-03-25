package com.bidstream.adapter.out.persistence.repository;

import com.bidstream.adapter.out.persistence.entity.AutoBidJpaEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoBidJpaRepository extends JpaRepository<AutoBidJpaEntity, UUID> {

    Optional<AutoBidJpaEntity> findByAuctionIdAndBidderId(UUID auctionId, UUID bidderId);

    List<AutoBidJpaEntity> findByAuctionIdAndActiveTrueOrderByCreatedAtAsc(UUID auctionId);
}
