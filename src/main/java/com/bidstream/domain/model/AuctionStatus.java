package com.bidstream.domain.model;

/**
 * See PDR §11.1 for the full state machine and transition diagram.
 */
public enum AuctionStatus {
    DRAFT,
    SCHEDULED,
    OPEN,
    EXTENDED,
    CLOSING,
    SOLD,
    UNSOLD,
    CANCELLED
}
