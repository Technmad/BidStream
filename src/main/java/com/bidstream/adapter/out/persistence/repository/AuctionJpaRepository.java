package com.bidstream.adapter.out.persistence.repository;

import com.bidstream.adapter.out.persistence.entity.AuctionJpaEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuctionJpaRepository extends JpaRepository<AuctionJpaEntity, UUID> {

    /** Due auctions the close-trigger scheduler should enqueue a CLOSE for (PDR §11.3). */
    @Query("""
            SELECT a FROM AuctionJpaEntity a
             WHERE a.status IN ('OPEN', 'EXTENDED') AND a.endTime <= :now
            """)
    List<AuctionJpaEntity> findDueForClose(@Param("now") Instant now);

    /**
     * Optimistic-lock update matching PDR §9.2's raw-SQL example exactly: persists only if the
     * stored version still equals {@code expectedVersion}. Returns the number of rows updated
     * (0 means someone else moved first).
     */
    @Modifying
    @Query("""
            UPDATE AuctionJpaEntity a
               SET a.currentPrice = :currentPrice,
                   a.currentWinnerId = :currentWinnerId,
                   a.status = :status,
                   a.endTime = :endTime,
                   a.version = :expectedVersion + 1
             WHERE a.id = :id AND a.version = :expectedVersion
            """)
    int updateWithOptimisticLock(
            @Param("id") UUID id,
            @Param("currentPrice") BigDecimal currentPrice,
            @Param("currentWinnerId") UUID currentWinnerId,
            @Param("status") String status,
            @Param("endTime") Instant endTime,
            @Param("expectedVersion") long expectedVersion);
}
