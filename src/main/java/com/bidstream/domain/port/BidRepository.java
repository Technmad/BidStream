package com.bidstream.domain.port;

import com.bidstream.domain.model.Bid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BidRepository {

    Bid save(Bid bid);

    Page<Bid> findByAuctionId(UUID auctionId, Pageable pageable);

    List<Bid> findByBidderId(UUID bidderId, Pageable pageable);

    boolean existsByIdempotencyKey(UUID auctionId, UUID bidderId, String idempotencyKey);
}
