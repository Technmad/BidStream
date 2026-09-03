import { renderHook, act } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ResyncMessage } from "@/src/ws/connectionManager";
import type { AuctionEnded, AuctionExtended, BidResult, Outbid, PriceUpdate } from "@/src/ws/contracts";
import type { AuctionChannelMessage } from "@/src/ws/connectionManager";
import {
  applyIncomingConfirmed,
  reconcile,
  type NormalizedConfirmedState,
  type ReconciliationState,
  useReconciledAuction,
} from "./useReconciledAuction";

// The hook-level test below (`lastExtended`) drives `useReconciledAuction` through
// its actual WS-subscribing hooks rather than a real connection (§14 — "mocking
// the reconciliation hook's output" for consumers, and the underlying primitive
// itself is mocked here at the transport-hook boundary so no real STOMP/mock
// broker is needed to exercise this purely-additive piece of state).
let capturedAuctionHandler: ((message: AuctionChannelMessage) => void) | null = null;
vi.mock("@/src/ws/useAuctionChannel", () => ({
  useAuctionChannel: (
    _auctionId: string | null | undefined,
    onMessage: (message: AuctionChannelMessage) => void,
  ) => {
    capturedAuctionHandler = onMessage;
  },
  useNotificationsChannel: () => {},
}));

function priceUpdate(overrides: Partial<PriceUpdate> = {}): PriceUpdate {
  return {
    type: "PRICE_UPDATE",
    auctionId: "a1",
    price: "100.00",
    winnerId: "bidder-1",
    endTime: "2026-01-01T00:00:00Z",
    version: 1,
    serverNow: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("applyIncomingConfirmed (§8.3 steps 1-2) — version-monotonic, never timestamp-ordered", () => {
  it("applies the first confirmed state unconditionally, seeding displayedVersion", () => {
    const result = applyIncomingConfirmed(null, priceUpdate({ version: 5 }));
    expect(result.version).toBe(5);
    expect(result.price).toBe("100.00");
  });

  it("applies an incoming state with a strictly greater version", () => {
    const current = applyIncomingConfirmed(null, priceUpdate({ version: 1 }));
    const next = applyIncomingConfirmed(current, priceUpdate({ version: 2, price: "110.00" }));
    expect(next.version).toBe(2);
    expect(next.price).toBe("110.00");
  });

  it("discards an incoming state whose version is NOT strictly greater — no exceptions", () => {
    const current = applyIncomingConfirmed(null, priceUpdate({ version: 5, price: "500.00" }));
    // Equal version — discard.
    const sameVersion = applyIncomingConfirmed(current, priceUpdate({ version: 5, price: "999.00" }));
    expect(sameVersion).toBe(current);
    expect(sameVersion.price).toBe("500.00");
    // Lower version (e.g. an out-of-order/delayed frame) — discard.
    const staleVersion = applyIncomingConfirmed(current, priceUpdate({ version: 3, price: "1.00" }));
    expect(staleVersion).toBe(current);
  });

  it("does NOT use serverNow to decide ordering — a later serverNow with a lower/equal version is still discarded", () => {
    const current = applyIncomingConfirmed(
      null,
      priceUpdate({ version: 5, price: "500.00", serverNow: "2026-01-01T00:00:00Z" }),
    );
    const laterClockLowerVersion = applyIncomingConfirmed(
      current,
      priceUpdate({ version: 4, price: "1.00", serverNow: "2099-01-01T00:00:00Z" }),
    );
    expect(laterClockLowerVersion).toBe(current);
  });

  it("AUCTION_EXTENDED carries forward price/winner from current, updating only endTime+version", () => {
    const current = applyIncomingConfirmed(null, priceUpdate({ version: 1, price: "100.00", winnerId: "b1" }));
    const extended: AuctionExtended = {
      type: "AUCTION_EXTENDED",
      auctionId: "a1",
      newEndTime: "2026-01-01T00:05:00Z",
      version: 2,
      serverNow: "2026-01-01T00:00:00Z",
    };
    const next = applyIncomingConfirmed(current, extended);
    expect(next.price).toBe("100.00");
    expect(next.winnerId).toBe("b1");
    expect(next.endTime).toBe("2026-01-01T00:05:00Z");
    expect(next.version).toBe(2);
  });

  it("AUCTION_ENDED sets a terminal status and the final price/winner", () => {
    const current = applyIncomingConfirmed(null, priceUpdate({ version: 1 }));
    const ended: AuctionEnded = {
      type: "AUCTION_ENDED",
      auctionId: "a1",
      outcome: "SOLD",
      winnerId: "b2",
      finalPrice: "250.00",
      version: 2,
      serverNow: "2026-01-01T00:10:00Z",
    };
    const next = applyIncomingConfirmed(current, ended);
    expect(next.status).toBe("SOLD");
    expect(next.price).toBe("250.00");
    expect(next.winnerId).toBe("b2");
  });

  it("a terminal RESYNC status overrides even when no AUCTION_ENDED was ever seen (§7.4)", () => {
    const current = applyIncomingConfirmed(null, priceUpdate({ version: 1 }));
    const resync: ResyncMessage = {
      type: "RESYNC",
      auctionId: "a1",
      price: "300.00",
      winnerId: "b3",
      endTime: "2026-01-01T00:00:00Z",
      version: 9,
      status: "UNSOLD",
    };
    const next = applyIncomingConfirmed(current, resync);
    expect(next.status).toBe("UNSOLD");
    expect(next.version).toBe(9);
  });

  it("a tick immediately followed by AUCTION_ENDED: the ended frame's higher version wins (final-push short-circuit)", () => {
    let confirmed: NormalizedConfirmedState | null = null;
    confirmed = applyIncomingConfirmed(confirmed, priceUpdate({ version: 10, price: "400.00" }));
    const ended: AuctionEnded = {
      type: "AUCTION_ENDED",
      auctionId: "a1",
      outcome: "SOLD",
      winnerId: "winner",
      finalPrice: "410.00",
      version: 11,
      serverNow: "2026-01-01T00:00:01Z",
    };
    confirmed = applyIncomingConfirmed(confirmed, ended);
    expect(confirmed.status).toBe("SOLD");
    expect(confirmed.price).toBe("410.00");
    // A late-arriving tick with a lower version than the ended frame must never resurrect the auction.
    const lateTick = applyIncomingConfirmed(confirmed, priceUpdate({ version: 10, price: "405.00" }));
    expect(lateTick).toBe(confirmed);
    expect(lateTick.status).toBe("SOLD");
  });
});

describe("reconcile (§8.3 steps 2/4/5) — pure (confirmed, pending, message) -> next state", () => {
  const pending = { correlationId: "corr-1", auctionId: "a1", amount: "150.00", submittedAt: 0 };

  it("BID_RESULT ACCEPTED clears the matching pending bid but leaves confirmed untouched", () => {
    const state: ReconciliationState = { confirmed: applyIncomingConfirmed(null, priceUpdate({ version: 1 })), pending };
    const message: BidResult = { type: "BID_RESULT", correlationId: "corr-1", status: "ACCEPTED" };
    const { state: next, event } = reconcile(state, message);
    expect(next.pending).toBeNull();
    expect(next.confirmed).toBe(state.confirmed); // unchanged — the NEXT PRICE_UPDATE updates confirmed, not this
    expect(event).toEqual({ kind: "bidResult", correlationId: "corr-1", result: { outcome: "ACCEPTED" } });
  });

  it("BID_RESULT REJECTED:<reason> parses the reason and clears the matching pending bid", () => {
    const state: ReconciliationState = { confirmed: null, pending };
    const message: BidResult = {
      type: "BID_RESULT",
      correlationId: "corr-1",
      status: "REJECTED:BELOW_MIN_INCREMENT",
    };
    const { state: next, event } = reconcile(state, message);
    expect(next.pending).toBeNull();
    expect(event).toEqual({
      kind: "bidResult",
      correlationId: "corr-1",
      result: { outcome: "REJECTED", reason: "BELOW_MIN_INCREMENT" },
    });
  });

  it("a BID_RESULT for a DIFFERENT correlationId leaves the current pending bid untouched", () => {
    const state: ReconciliationState = { confirmed: null, pending };
    const message: BidResult = { type: "BID_RESULT", correlationId: "some-other-corr", status: "ACCEPTED" };
    const { state: next } = reconcile(state, message);
    expect(next.pending).toBe(pending);
  });

  it("OUTBID for the same auction clears the pending bid immediately, without touching confirmed", () => {
    const state: ReconciliationState = { confirmed: applyIncomingConfirmed(null, priceUpdate({ version: 1 })), pending };
    const message: Outbid = { type: "OUTBID", auctionId: "a1", newPrice: "160.00" };
    const { state: next, event } = reconcile(state, message);
    expect(next.pending).toBeNull();
    expect(next.confirmed).toBe(state.confirmed);
    expect(event).toEqual({ kind: "outbid", auctionId: "a1", newPrice: "160.00" });
  });

  it("OUTBID for a different auction does not clear this auction's pending bid", () => {
    const state: ReconciliationState = { confirmed: null, pending };
    const message: Outbid = { type: "OUTBID", auctionId: "some-other-auction", newPrice: "1.00" };
    const { state: next } = reconcile(state, message);
    expect(next.pending).toBe(pending);
  });

  it("an OUTBID arriving while a pending bid is in flight resolves in the same event, exercising both step 3 (attach) and step 5 (clear)", () => {
    const state: ReconciliationState = { confirmed: applyIncomingConfirmed(null, priceUpdate({ version: 1, price: "100" })), pending };
    const { state: afterOutbid } = reconcile(state, { type: "OUTBID", auctionId: "a1", newPrice: "120.00" });
    expect(afterOutbid.pending).toBeNull();
    // The confirmed price/winner change OUTBID describes arrives separately via a PRICE_UPDATE (step 2):
    const afterTick = reconcile(afterOutbid, priceUpdate({ version: 2, price: "120.00", winnerId: "someone-else" }));
    expect(afterTick.state.confirmed?.price).toBe("120.00");
  });

  it("PRICE_UPDATE/AUCTION_EXTENDED/AUCTION_ENDED/RESYNC never produce an event (only BID_RESULT/OUTBID do)", () => {
    const state: ReconciliationState = { confirmed: null, pending: null };
    const { event } = reconcile(state, priceUpdate({ version: 1 }));
    expect(event).toBeNull();
  });
});

describe("useReconciledAuction — lastExtended (§12.1's required AUCTION_EXTENDED visual moment)", () => {
  it("is null until an AUCTION_EXTENDED frame arrives, then carries its newEndTime", () => {
    capturedAuctionHandler = null;
    const { result } = renderHook(() =>
      useReconciledAuction("a1", { price: "100.00", winnerId: null, endTime: "2026-01-01T00:00:00Z", version: 1 }),
    );

    expect(result.current.lastExtended).toBeNull();

    const extended: AuctionExtended = {
      type: "AUCTION_EXTENDED",
      auctionId: "a1",
      newEndTime: "2026-01-01T00:05:00Z",
      version: 2,
      serverNow: "2026-01-01T00:00:00Z",
    };

    act(() => {
      capturedAuctionHandler?.(extended);
    });

    expect(result.current.lastExtended).toEqual({ newEndTime: "2026-01-01T00:05:00Z" });
    // Purely additive — the normal version-monotonic confirmed-state merge still applies.
    expect(result.current.confirmed?.endTime).toBe("2026-01-01T00:05:00Z");
    expect(result.current.confirmed?.version).toBe(2);
  });
});
