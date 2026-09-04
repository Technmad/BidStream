"use client";

import Link from "next/link";
import { useMemo } from "react";
import type { AuctionResponse } from "@/src/api/auctions";
import { displayHandle } from "@/src/lib/displayHandle";
import {
  useReconciledAuction,
  type NormalizedConfirmedState,
} from "@/src/reconciliation/useReconciledAuction";

/**
 * Maps a Browse list item's static REST fields onto `useReconciledAuction`'s
 * first confirmed state — the exact same conversion `AuctionRoom.tsx` does for
 * the detail page (`toInitialConfirmed`), reused here per card (§12.2, §18:
 * "proves §11 isn't just a paragraph in a design doc"). Every live-updating
 * price on this screen goes through the same one merge point as the detail page.
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

const STATUS_LABEL: Record<string, string> = {
  DRAFT: "Draft",
  SCHEDULED: "Scheduled",
  OPEN: "Open",
  EXTENDED: "Extended",
  CLOSING: "Closing",
  SOLD: "Sold",
  UNSOLD: "Unsold",
  CANCELLED: "Cancelled",
};

/**
 * One auction's card (§12.2). Subscribes to `/topic/auctions/{id}` for as long
 * as it's rendered — `useAuctionChannel`'s effect cleanup (invoked when this
 * component unmounts, e.g. paginated away) already satisfies "unsubscribed on
 * scroll-out or unmount" (§7.1 point 2, §12.2) — nothing extra to build here.
 */
export function AuctionCard({ auction }: { auction: AuctionResponse }) {
  const initialConfirmed = useMemo(
    () => toInitialConfirmed(auction),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentionally only the FIRST value matters (see AuctionRoom.tsx's identical pattern); useReconciledAuction only reads this on its own first render.
    [auction.id],
  );

  const view = useReconciledAuction(auction.id, initialConfirmed);
  const confirmed = view.confirmed;

  const price = confirmed?.price ?? auction.currentPrice;
  const winnerId = confirmed?.winnerId ?? auction.currentWinnerId;
  const status = confirmed?.status ?? auction.status;

  return (
    <li className="flex flex-col gap-2 rounded-lg border border-gray-300 p-4 dark:border-gray-700">
      <Link href={`/auctions/${auction.id}`} className="font-medium underline-offset-2 hover:underline">
        {auction.title}
      </Link>

      <div aria-live="polite" className="flex items-baseline justify-between gap-2">
        <span className="text-xl font-bold tabular-nums">${price}</span>
        <span className="text-xs uppercase text-gray-500">{STATUS_LABEL[status] ?? status}</span>
      </div>

      <p className="text-xs text-gray-500">
        {winnerId ? <>High bidder: {displayHandle(winnerId)}</> : "No bids yet"}
      </p>

      <p className="text-xs text-gray-500">
        Ends {new Date(confirmed?.endTime ?? auction.endTime).toLocaleString()}
      </p>
    </li>
  );
}
