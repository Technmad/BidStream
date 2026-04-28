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

    /**
     * PDR §14.4 {@code GET /me/watching}: every auction the caller has ever watched (§8.4's
     * {@code watches} table), all statuses, ordered by end time - a durable bookmark list, not
     * filtered to only-open auctions, since a watcher may still care about one that already
     * closed.
     */
    @Query(value = "SELECT a.* FROM auctions a JOIN watches w ON w.auction_id = a.id "
            + "WHERE w.user_id = :userId ORDER BY a.end_time ASC",
            countQuery = "SELECT count(*) FROM watches w WHERE w.user_id = :userId",
            nativeQuery = true)
    Page<AuctionJpaEntity> findWatchedByUser(@Param("userId") UUID userId, Pageable pageable);
}
