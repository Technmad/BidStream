import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  PENDING_BID_TIMEOUT_MS,
  submitPendingBid,
  usePendingBidsStore,
  type PendingBid,
} from "./pendingBids";

const bid: PendingBid = {
  correlationId: "corr-1",
  auctionId: "auction-1",
  amount: "150.00",
  submittedAt: Date.now(),
};

describe("pendingBids store (§8.2, §8.4)", () => {
  beforeEach(() => {
    usePendingBidsStore.getState().reset();
  });

  it("attaches as this auction's pending bid once added", () => {
    usePendingBidsStore.getState().addPendingBid(bid);
    expect(usePendingBidsStore.getState().getPendingBidForAuction("auction-1")).toEqual(bid);
    expect(usePendingBidsStore.getState().getPendingBidForAuction("other-auction")).toBeNull();
  });

  it("removePendingBid clears both the bid and its timed-out flag", () => {
    usePendingBidsStore.getState().addPendingBid(bid);
    usePendingBidsStore.getState().markTimedOut(bid.correlationId);
    usePendingBidsStore.getState().removePendingBid(bid.correlationId);
    expect(usePendingBidsStore.getState().getPendingBidForAuction("auction-1")).toBeNull();
    expect(usePendingBidsStore.getState().isTimedOut(bid.correlationId)).toBe(false);
  });

  it("markTimedOut is a no-op for a correlationId that isn't (or is no longer) pending", () => {
    usePendingBidsStore.getState().markTimedOut("never-existed");
    expect(usePendingBidsStore.getState().isTimedOut("never-existed")).toBe(false);
  });
});

describe("submitPendingBid — §8.4's 10s timeout", () => {
  beforeEach(() => {
    usePendingBidsStore.getState().reset();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not mark the bid timed out before 10s elapses", () => {
    submitPendingBid(bid);
    vi.advanceTimersByTime(PENDING_BID_TIMEOUT_MS - 1);
    expect(usePendingBidsStore.getState().isTimedOut(bid.correlationId)).toBe(false);
  });

  it("marks the bid timed out at exactly the 10s mark, without removing it (never reinterpreted as failure)", () => {
    submitPendingBid(bid);
    vi.advanceTimersByTime(PENDING_BID_TIMEOUT_MS);
    expect(usePendingBidsStore.getState().isTimedOut(bid.correlationId)).toBe(true);
    // Still present — a timeout is a UI label change ("still processing"), not a removal/failure/retry.
    expect(usePendingBidsStore.getState().getPendingBidForAuction(bid.auctionId)).toEqual(bid);
  });

  it("the cleanup function returned by submitPendingBid cancels the pending timeout", () => {
    const cancel = submitPendingBid(bid);
    cancel();
    vi.advanceTimersByTime(PENDING_BID_TIMEOUT_MS + 1000);
    expect(usePendingBidsStore.getState().isTimedOut(bid.correlationId)).toBe(false);
  });

  it("a bid resolved (removed) before 10s never gets marked timed out afterward", () => {
    submitPendingBid(bid);
    usePendingBidsStore.getState().removePendingBid(bid.correlationId);
    vi.advanceTimersByTime(PENDING_BID_TIMEOUT_MS + 1000);
    expect(usePendingBidsStore.getState().isTimedOut(bid.correlationId)).toBe(false);
  });
});
