package com.bidstream.application;

import com.bidstream.adapter.out.persistence.mapper.AuctionMapper;
import com.bidstream.adapter.out.persistence.repository.AuctionListingJpaRepository;
import com.bidstream.common.ConflictException;
import com.bidstream.common.NotFoundException;
import com.bidstream.domain.model.AuctionItem;
import com.bidstream.domain.model.AuctionStatus;
import com.bidstream.domain.model.Money;
import com.bidstream.domain.port.AuctionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuctionService {

    private final AuctionRepository auctionRepository;
    private final AuctionListingJpaRepository listingRepository;

    public AuctionService(AuctionRepository auctionRepository,
                           AuctionListingJpaRepository listingRepository) {
        this.auctionRepository = auctionRepository;
        this.listingRepository = listingRepository;
    }

    public Page<AuctionItem> search(AuctionStatus status, UUID categoryId, UUID sellerId, String q,
                                     Pageable pageable) {
        return listingRepository
                .search(status == null ? null : status.name(), categoryId, sellerId,
                        (q == null || q.isBlank()) ? null : q, pageable)
                .map(AuctionMapper::toDomain);
    }

    /** PDR §14.4 {@code GET /me/watching}: every auction the caller has watched, all statuses. */
    public Page<AuctionItem> watchedByUser(UUID userId, Pageable pageable) {
        return listingRepository.findWatchedByUser(userId, pageable).map(AuctionMapper::toDomain);
    }

    @Transactional
    public AuctionItem create(UUID sellerId, UUID categoryId, String title, String description,
                               Money startingPrice, Money reservePrice, Money minIncrement,
                               Instant startTime, Instant endTime, int antiSnipeSeconds) {
        AuctionStatus initialStatus = startTime.isAfter(Instant.now())
                ? AuctionStatus.SCHEDULED
                : AuctionStatus.OPEN;
        AuctionItem auction = new AuctionItem(
                UUID.randomUUID(), sellerId, categoryId, title, description,
                startingPrice, reservePrice, minIncrement, startingPrice, null,
                initialStatus, startTime, endTime, antiSnipeSeconds, 0L);
        return auctionRepository.save(auction);
    }

    public AuctionItem getById(UUID id) {
        return auctionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Auction not found: " + id));
    }

    @Transactional
    public AuctionItem update(UUID id, UUID requesterId, String title, String description,
                               Instant startTime, Instant endTime) {
        AuctionItem auction = getById(id);
        requireOwner(auction, requesterId);
        if (auction.status() != AuctionStatus.DRAFT && auction.status() != AuctionStatus.SCHEDULED) {
            throw new ConflictException("Auction can only be edited before it opens");
        }
        AuctionItem updated = new AuctionItem(
                auction.id(), auction.sellerId(), auction.categoryId(),
                title != null ? title : auction.title(),
                description != null ? description : auction.description(),
                auction.startingPrice(), auction.reservePrice(), auction.minIncrement(),
                auction.currentPrice(), auction.currentWinnerId(), auction.status(),
                startTime != null ? startTime : auction.startTime(),
                endTime != null ? endTime : auction.endTime(),
                auction.antiSnipeSeconds(), auction.version());
        return auctionRepository.save(updated);
    }

    @Transactional
    public void cancel(UUID id, UUID requesterId, boolean requesterIsAdmin) {
        AuctionItem auction = getById(id);
        if (!requesterIsAdmin) {
            requireOwner(auction, requesterId);
        }
        if (auction.status() != AuctionStatus.SCHEDULED && auction.status() != AuctionStatus.OPEN) {
            throw new ConflictException("Only scheduled or open auctions can be cancelled");
        }
        AuctionItem cancelled = new AuctionItem(
                auction.id(), auction.sellerId(), auction.categoryId(), auction.title(),
                auction.description(), auction.startingPrice(), auction.reservePrice(),
                auction.minIncrement(), auction.currentPrice(), auction.currentWinnerId(),
                AuctionStatus.CANCELLED, auction.startTime(), auction.endTime(),
                auction.antiSnipeSeconds(), auction.version());
        auctionRepository.save(cancelled);
    }

    private void requireOwner(AuctionItem auction, UUID requesterId) {
        if (!auction.sellerId().equals(requesterId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the seller who created this auction may modify it");
        }
    }
}
