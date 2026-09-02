import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { usePendingBidsStore } from "@/src/state/pendingBids";
import { useConnectionStore } from "@/src/state/connectionStore";
import type { AuctionResponse } from "@/src/api/auctions";

// The manager's resync (§7.4) hits the REST API — mocked so these integration
// tests exercise only the WS transport + reconciliation wiring, per a mock STOMP
// broker (§14.3), never a real network call.
vi.mock("@/src/api/auctions", () => ({
  getAuction: vi.fn(),
}));

import { getAuction } from "@/src/api/auctions";
import { ConnectionManager, auctionTopicDestination, NOTIFICATIONS_DESTINATION } from "./connectionManager";
import { MockBroker } from "@/src/test/mockBroker";

const getAuctionMock = vi.mocked(getAuction);

function auctionResponse(overrides: Partial<AuctionResponse> = {}): AuctionResponse {
  return {
    id: "a1",
    sellerId: "seller-1",
    categoryId: null,
    title: "Test auction",
    description: null,
    startingPrice: "10.00",
    reservePrice: null,
    minIncrement: "1.00",
    currentPrice: "100.00",
    currentWinnerId: null,
    status: "OPEN",
    startTime: "2026-01-01T00:00:00Z",
    endTime: "2026-01-01T01:00:00Z",
    antiSnipeSeconds: 30,
    version: 1,
    ...overrides,
  };
}

/** Polls with real timers until `check()` returns true, or fails after `timeoutMs`. */
async function waitUntil(check: () => boolean, timeoutMs = 2000): Promise<void> {
  const start = Date.now();
  while (!check()) {
    if (Date.now() - start > timeoutMs) throw new Error("waitUntil timed out");
    await new Promise((r) => setTimeout(r, 5));
  }
}

describe("ConnectionManager integration (§14.3, mock STOMP broker)", () => {
  let broker: MockBroker;
  let manager: ConnectionManager;

  beforeEach(() => {
    broker = new MockBroker();
    manager = new ConnectionManager(broker.webSocketFactory);
    getAuctionMock.mockReset();
    getAuctionMock.mockResolvedValue(auctionResponse());
    useConnectionStore.getState().reset();
    usePendingBidsStore.getState().reset();
  });

  afterEach(() => {
    manager.disconnect();
  });

  it("connects and reaches 'live'", async () => {
    manager.connect();
    await waitUntil(() => useConnectionStore.getState().status === "live");
    expect(useConnectionStore.getState().status).toBe("live");
  });

  it("a tick immediately followed by AUCTION_ENDED: the ended frame wins (final-push short-circuit)", async () => {
    manager.connect();
    await waitUntil(() => useConnectionStore.getState().status === "live");

    const received: unknown[] = [];
    const unsubscribe = manager.subscribe(auctionTopicDestination("a1"), (msg) => received.push(msg));
    await waitUntil(() => broker.activeDestinations().includes(auctionTopicDestination("a1")));

    // Publish a tick immediately followed by the terminal AUCTION_ENDED frame, back to back,
    // simulating them landing in the same short window (§14's named scenario).
    broker.publish(auctionTopicDestination("a1"), {
      type: "PRICE_UPDATE",
      auctionId: "a1",
      price: "150.00",
      winnerId: "b1",
      endTime: "2026-01-01T01:00:00Z",
      version: 5,
      serverNow: "2026-01-01T00:59:59Z",
    });
    broker.publish(auctionTopicDestination("a1"), {
      type: "AUCTION_ENDED",
      auctionId: "a1",
      outcome: "SOLD",
      winnerId: "b1",
      finalPrice: "155.00",
      version: 6,
      serverNow: "2026-01-01T01:00:00Z",
    });

    await waitUntil(() => received.length >= 2);
    expect(received[0]).toMatchObject({ type: "PRICE_UPDATE", version: 5 });
    expect(received[1]).toMatchObject({ type: "AUCTION_ENDED", version: 6 });

    unsubscribe();
  });

  it("an OUTBID arriving while a pending bid is in flight is delivered on the notifications queue", async () => {
    manager.connect();
    await waitUntil(() => useConnectionStore.getState().status === "live");

    usePendingBidsStore.getState().addPendingBid({
      correlationId: "corr-1",
      auctionId: "a1",
      amount: "150.00",
      submittedAt: Date.now(),
    });

    const received: unknown[] = [];
    manager.subscribe(NOTIFICATIONS_DESTINATION, (msg) => received.push(msg));
    await waitUntil(() => broker.activeDestinations().includes(NOTIFICATIONS_DESTINATION));

    broker.publish(NOTIFICATIONS_DESTINATION, {
      type: "OUTBID",
      auctionId: "a1",
      newPrice: "160.00",
    });

    await waitUntil(() => received.length >= 1);
    expect(received[0]).toEqual({ type: "OUTBID", auctionId: "a1", newPrice: "160.00" });
    // The pending bid itself is only cleared by useReconciledAuction's handler
    // (§8.3 step 5) — the manager's own job here is strictly transport/fan-out,
    // proven by the message actually arriving at the subscriber.
  });

  it("a simulated disconnect followed by reconnect triggers a resync (§7.4) before resubscribing", async () => {
    const received: unknown[] = [];
    // Subscribe before the connection is live, matching how a mounted component
    // (useAuctionChannel) typically registers interest — so the initial connect's
    // own resync (§7.4 applies uniformly, first connect included) has something
    // to resync.
    manager.subscribe(auctionTopicDestination("a1"), (msg) => received.push(msg));
    manager.connect();
    await waitUntil(() => useConnectionStore.getState().status === "live");
    await waitUntil(() => broker.activeDestinations().includes(auctionTopicDestination("a1")));
    expect(getAuctionMock).toHaveBeenCalledTimes(1); // the initial connect's own resync (harmless, idempotent)

    // Simulate the connection dropping mid-bid.
    broker.simulateDisconnectAll();
    await waitUntil(() => useConnectionStore.getState().status === "reconnecting");

    // Change what the backend would now report, so we can distinguish "resync happened" from stale data.
    getAuctionMock.mockResolvedValue(auctionResponse({ version: 7, currentPrice: "999.00", status: "SOLD" }));

    await waitUntil(() => useConnectionStore.getState().status === "live", 10_000);
    await waitUntil(
      () => received.filter((m) => (m as { type: string }).type === "RESYNC").length >= 2,
      5000,
    );

    // The LAST resync is the one from the post-reconnect resync (the first came
    // from the initial connect, before the drop).
    const resyncMessages = received.filter((m) => (m as { type: string }).type === "RESYNC");
    const resyncMsg = resyncMessages[resyncMessages.length - 1] as {
      version: number;
      price: string;
      status: string;
    };
    expect(resyncMsg.version).toBe(7);
    expect(resyncMsg.price).toBe("999.00");
    // §7.4: a terminal REST status on resync overrides a possibly-missed AUCTION_ENDED.
    expect(resyncMsg.status).toBe("SOLD");
    expect(getAuctionMock.mock.calls.length).toBeGreaterThanOrEqual(2);
  }, 15_000);
});
