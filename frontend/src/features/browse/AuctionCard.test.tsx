import { render, cleanup } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuctionResponse } from "@/src/api/auctions";
import { AuctionCard } from "./AuctionCard";

/**
 * §12.2/§18's coalescing crux, honestly scoped: a true multi-card coalescing
 * integration test would need the reference-counted `ConnectionManager`
 * connected to a mock STOMP broker with several `AuctionCard`s sharing the
 * connection (`connectionManager.integration.test.ts` already exercises that
 * registry directly, at the manager level). `getConnectionManager()`'s module
 * singleton isn't injectable from here without either a real connect or
 * reaching into module-private state, so — per the task brief's named
 * fallback — this test instead proves the more modest, still load-bearing
 * claim: each `AuctionCard` subscribes to its own auction's live channel via
 * the shared `useAuctionChannel`/`getConnectionManager()` machinery (never a
 * per-card ad hoc `setInterval`/direct WebSocket handling of its own), and
 * unsubscribes on unmount — exactly the "subscribe on mount, unsubscribe on
 * scroll-out/unmount" behavior §12.2 requires, and the mechanism the
 * reference-counted registry (§7.1 point 2) is built on top of.
 */

const subscribeCalls: string[] = [];
const unsubscribeCalls: string[] = [];

vi.mock("@/src/ws/connectionManager", () => ({
  NOTIFICATIONS_DESTINATION: "/user/queue/notifications",
  auctionTopicDestination: (auctionId: string) => `/topic/auctions/${auctionId}`,
  getConnectionManager: () => ({
    subscribe: (destination: string) => {
      subscribeCalls.push(destination);
      return () => {
        unsubscribeCalls.push(destination);
      };
    },
  }),
}));

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

describe("AuctionCard — subscribes/unsubscribes via the shared connection manager (§12.2, §7.1)", () => {
  beforeEach(() => {
    subscribeCalls.length = 0;
    unsubscribeCalls.length = 0;
  });

  afterEach(() => {
    cleanup();
  });

  it("mounting several cards for different auctions each subscribes to its own /topic/auctions/{id} destination — no ad hoc per-card polling", () => {
    const auctions = [
      auctionResponse({ id: "a1" }),
      auctionResponse({ id: "a2" }),
      auctionResponse({ id: "a3" }),
    ];

    render(
      <ul>
        {auctions.map((a) => (
          <AuctionCard key={a.id} auction={a} />
        ))}
      </ul>,
    );

    expect(subscribeCalls).toContain("/topic/auctions/a1");
    expect(subscribeCalls).toContain("/topic/auctions/a2");
    expect(subscribeCalls).toContain("/topic/auctions/a3");
    // Each card also subscribes to the shared per-user notifications queue via
    // useReconciledAuction's useNotificationsChannel — same shared machinery,
    // not a separate transport per card.
    expect(subscribeCalls.filter((d) => d === "/user/queue/notifications").length).toBe(3);
  });

  it("unmounting a card unsubscribes its channel — a paginated-away card stops costing anything (§12.2)", () => {
    const auction = auctionResponse({ id: "a1" });
    const { unmount } = render(<AuctionCard auction={auction} />);

    expect(subscribeCalls).toContain("/topic/auctions/a1");
    expect(unsubscribeCalls).not.toContain("/topic/auctions/a1");

    unmount();

    expect(unsubscribeCalls).toContain("/topic/auctions/a1");
    expect(unsubscribeCalls).toContain("/user/queue/notifications");
  });
});
