package com.bidstream.domain.port;

import com.bidstream.domain.model.AutoBid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutoBidRepository {

    AutoBid save(AutoBid autoBid);

    Optional<AutoBid> findByAuctionIdAndBidderId(UUID auctionId, UUID bidderId);

    /** Active auto-bids for an auction, ordered earliest-first (PDR §12.1 tie-break). */
    List<AutoBid> findActiveByAuctionId(UUID auctionId);

    void deactivate(UUID auctionId, UUID bidderId);
}
