"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { auctionQueryKey, getAuction, type AuctionResponse } from "@/src/api/auctions";
import { useAuth } from "@/src/features/auth/useAuth";
import { displayHandle } from "@/src/lib/displayHandle";
import {
  useReconciledAuction,
  type NormalizedConfirmedState,
} from "@/src/reconciliation/useReconciledAuction";
import { AutoBidForm } from "./AutoBidForm";
import { BidForm } from "./BidForm";
import { BidHistoryPanel } from "./BidHistoryPanel";
import { ConnectionBadge } from "./ConnectionBadge";
import { Countdown } from "./Countdown";
import { ExtendedBanner } from "./ExtendedBanner";
import { LeaderboardPanel } from "./LeaderboardPanel";

/**
 * §5.2/§12.1's literal requirement: the SSR'd/hydrated `GET /auctions/{id}`
 * result *is* `useReconciledAuction`'s first confirmed state — not a second,
 * independent render that could disagree with it. This mapping is that
 * conversion, applied once as `useReconciledAuction`'s `initialConfirmed`
 * argument (its `useState` initializer only ever reads its first call's value).
 */
function toInitialConfirmed(auction: AuctionResponse): NormalizedConfirmedState {
  return {
    price: auction.currentPrice,
    winnerId: auction.currentWinnerId,
    endTime: auction.endTime,
    version: auction.version,
    status: auction.status,
  };
}

const OUTCOME_LABEL: Record<string, string> = {
  SOLD: "Sold",
  UNSOLD: "Unsold — reserve not met",
  CANCELLED: "Cancelled",
};

/**
 * The Live Auction Room orchestrator (§12.1) — a Client Component consumed only
 * by `app/auctions/[id]/page.tsx`. Reads the hydrated auction via `useQuery`
 * (static fields: title/description/sellerId/minIncrement/startingPrice, and the
 * seed for `useReconciledAuction`'s first confirmed state), then reads all live
 * state exclusively through `useReconciledAuction` — never raw WS/REST directly.
 */
export function AuctionRoom({ auctionId }: { auctionId: string }) {
  const { data: auction } = useQuery({
    queryKey: auctionQueryKey(auctionId),
    queryFn: () => getAuction(auctionId),
  });

  const { accessToken, isAuthenticated } = useAuth();

  const initialConfirmed = useMemo(
    () => (auction ? toInitialConfirmed(auction) : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentionally only the FIRST value matters (see toInitialConfirmed's doc comment); useReconciledAuction only reads this on its own first render.
    [],
  );

  const view = useReconciledAuction(auctionId, initialConfirmed);

  if (!auction) {
    // Should not normally happen post-hydration (§5.2), but keep this route from
    // crashing if the cache was ever empty (e.g. a direct client navigation that
    // skipped the SSR fetch somehow).
    return <p className="p-6 text-sm text-gray-500">Loading auction…</p>;
  }

  const confirmed = view.confirmed;
  const price = confirmed?.price ?? auction.currentPrice;
  const winnerId = confirmed?.winnerId ?? auction.currentWinnerId;
  const endTime = confirmed?.endTime ?? auction.endTime;

  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 px-4 py-10">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold">{auction.title}</h1>
          {auction.description && (
            <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">{auction.description}</p>
          )}
        </div>
        <ConnectionBadge connectionState={view.connectionState} />
      </div>

      {view.lastExtended && (
        <ExtendedBanner key={view.lastExtended.newEndTime} newEndTime={view.lastExtended.newEndTime} />
      )}

      {view.isEnded && confirmed?.status && (
        <div role="alert" aria-live="assertive" className="rounded border-2 border-gray-400 bg-gray-100 px-4 py-3 text-sm font-medium dark:bg-gray-800">
          Auction {OUTCOME_LABEL[confirmed.status] ?? confirmed.status}
          {winnerId && confirmed.status === "SOLD" && <> — won by {displayHandle(winnerId)}</>}
        </div>
      )}

      <div className="flex items-center justify-between gap-4 rounded-lg border border-gray-300 p-4 dark:border-gray-700">
        <div aria-live="polite" className="flex flex-col gap-1">
          <span className="text-xs uppercase text-gray-500">Current price</span>
          <span className="text-3xl font-bold tabular-nums">${price}</span>
          <span className="text-sm text-gray-500">
            High bidder: {winnerId ? displayHandle(winnerId) : "No bids yet"}
          </span>
        </div>
        <div className="flex flex-col items-end gap-1">
          <span className="text-xs uppercase text-gray-500">Time remaining</span>
          <Countdown endTime={endTime} isEnded={view.isEnded} />
        </div>
      </div>

      {view.lastOutbid && (
        <p role="alert" aria-live="assertive" className="rounded border border-red-400 bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-900/30 dark:text-red-300">
          You&apos;ve been outbid — the price is now ${view.lastOutbid.newPrice}.
        </p>
      )}

      <div className="grid gap-6 sm:grid-cols-2">
        <div className="flex flex-col gap-4">
          <BidForm
            auctionId={auctionId}
            confirmedPrice={price}
            minIncrement={auction.minIncrement}
            myPendingBid={view.myPendingBid}
            pendingBidStatus={view.pendingBidStatus}
            lastBidResult={view.lastBidResult}
            isEnded={view.isEnded}
            isAuthenticated={isAuthenticated}
            accessToken={accessToken}
          />
          <AutoBidForm
            auctionId={auctionId}
            isEnded={view.isEnded}
            isAuthenticated={isAuthenticated}
            accessToken={accessToken}
          />
        </div>
        <div className="flex flex-col gap-6">
          <LeaderboardPanel auctionId={auctionId} />
          <BidHistoryPanel auctionId={auctionId} />
        </div>
      </div>
    </main>
  );
}
