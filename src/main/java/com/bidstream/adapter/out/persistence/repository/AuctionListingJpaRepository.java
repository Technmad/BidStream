package com.bidstream.adapter.out.persistence.repository;

import com.bidstream.adapter.out.persistence.entity.AuctionJpaEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Read-side listing query, kept separate from {@link AuctionJpaRepository} since it is a
 * filtered browse query (PDR §14.1 {@code GET /auctions}) rather than aggregate persistence.
 */
@Repository
public interface AuctionListingJpaRepository extends org.springframework.data.repository.Repository<AuctionJpaEntity, UUID> {

    @Query("""
            SELECT a FROM AuctionJpaEntity a
             WHERE (:status IS NULL OR a.status = :status)
               AND (:categoryId IS NULL OR a.categoryId = :categoryId)
            """)
    Page<AuctionJpaEntity> search(
            @Param("status") String status,
            @Param("categoryId") UUID categoryId,
            Pageable pageable);
}
