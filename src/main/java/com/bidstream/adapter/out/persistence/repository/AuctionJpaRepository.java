package com.bidstream.adapter.out.persistence.repository;

import com.bidstream.adapter.out.persistence.entity.AuctionJpaEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuctionJpaRepository extends JpaRepository<AuctionJpaEntity, UUID> {

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
