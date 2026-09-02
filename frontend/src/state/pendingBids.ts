"use client";

import { create } from "zustand";

/**
 * Pending-bid overlay state (§8.2, §8.4). A `PendingBid` is never the confirmed
 * price/winner — it's an annotation `useReconciledAuction` (§8.3) attaches on top
 * of confirmed state, keyed by `correlationId` (§4.1: the bid `POST` response
 * carries `{bidId, status:"PENDING", correlationId}`).
 */

/** Exact shape from §8.2. */
export type PendingBid = {
  correlationId: string;
  auctionId: string;
  amount: string;
  /** Client timestamp, for the 10s timeout (§8.4). */
  submittedAt: number;
};

/** §8.4's 10-second "still processing" timeout, in ms. */
export const PENDING_BID_TIMEOUT_MS = 10_000;

type PendingBidsState = {
  bids: Map<string, PendingBid>;
  /**
   * `correlationId`s whose 10s timeout (§8.4) has elapsed with no `BID_RESULT` yet.
   * NEVER reinterpreted as failure and never auto-retried (§8.4) — this only flips
   * the UI's own label from "confirming…" to "still processing…"; the bid stays in
   * `bids` untouched until a real `BID_RESULT`/`OUTBID`/superseding `PRICE_UPDATE`
   * resolves it (§8.1, §8.3).
   */
  timedOut: Set<string>;

  addPendingBid: (bid: PendingBid) => void;
  removePendingBid: (correlationId: string) => void;
  markTimedOut: (correlationId: string) => void;
  /** This user's own in-flight bid for an auction, if any (§8.3 step 3) — at most one is expected per auction. */
  getPendingBidForAuction: (auctionId: string) => PendingBid | null;
  isTimedOut: (correlationId: string) => boolean;
  reset: () => void;
};

export const usePendingBidsStore = create<PendingBidsState>((set, get) => ({
  bids: new Map(),
  timedOut: new Set(),

  addPendingBid: (bid) => {
    const bids = new Map(get().bids);
    bids.set(bid.correlationId, bid);
    set({ bids });
  },

  removePendingBid: (correlationId) => {
    const bids = new Map(get().bids);
    bids.delete(correlationId);
    const timedOut = new Set(get().timedOut);
    timedOut.delete(correlationId);
    set({ bids, timedOut });
  },

  markTimedOut: (correlationId) => {
    if (!get().bids.has(correlationId)) return; // already resolved — the timer fired too late, no-op
    const timedOut = new Set(get().timedOut);
    timedOut.add(correlationId);
    set({ timedOut });
  },

  getPendingBidForAuction: (auctionId) => {
    for (const bid of get().bids.values()) {
      if (bid.auctionId === auctionId) return bid;
    }
    return null;
  },

  isTimedOut: (correlationId) => get().timedOut.has(correlationId),

  reset: () => set({ bids: new Map(), timedOut: new Set() }),
}));

/**
 * Registers a bid and schedules its §8.4 timeout. Returns a cleanup function that
 * cancels the timer (call it if the bid resolves before the timeout, though
 * `markTimedOut` is itself a safe no-op on an already-resolved bid, so cleanup is
 * a tidiness measure, not a correctness requirement).
 */
export function submitPendingBid(
  bid: PendingBid,
  timeoutMs: number = PENDING_BID_TIMEOUT_MS,
): () => void {
  usePendingBidsStore.getState().addPendingBid(bid);
  const timer = setTimeout(() => {
    usePendingBidsStore.getState().markTimedOut(bid.correlationId);
  }, timeoutMs);
  return () => clearTimeout(timer);
}
