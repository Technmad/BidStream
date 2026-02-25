package com.bidstream.adapter.in.ws.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Targeted, per-user messages pushed to {@code /user/queue/notifications} (PDR §15.2). */
public sealed interface NotificationMessage {

    record Outbid(String type, UUID auctionId, BigDecimal newPrice) implements NotificationMessage {
        public static Outbid of(UUID auctionId, BigDecimal newPrice) {
            return new Outbid("OUTBID", auctionId, newPrice);
        }
    }

    record BidResult(String type, UUID correlationId, String status) implements NotificationMessage {
        public static BidResult of(UUID correlationId, String status) {
            return new BidResult("BID_RESULT", correlationId, status);
        }
    }
}
