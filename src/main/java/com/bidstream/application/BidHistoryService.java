package com.bidstream.application;

import com.bidstream.domain.model.Bid;
import com.bidstream.domain.port.BidRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class BidHistoryService {

    private final BidRepository bidRepository;

    public BidHistoryService(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    public Page<Bid> forAuction(UUID auctionId, Pageable pageable) {
        return bidRepository.findByAuctionId(auctionId, pageable);
    }

    public List<Bid> forBidder(UUID bidderId, Pageable pageable) {
        return bidRepository.findByBidderId(bidderId, pageable);
    }
}
