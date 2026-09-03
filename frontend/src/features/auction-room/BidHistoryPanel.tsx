"use client";

import { useQuery } from "@tanstack/react-query";
import { bidHistoryQueryKey, listBids } from "@/src/api/auctions";
import { displayHandle } from "@/src/lib/displayHandle";

/**
 * §12.1's bid-history panel — client-fetched (§12.1: "default to client-fetched
 * since they're not the page's SEO-relevant content and gain little from SSR").
 * `GET /auctions/{id}/bids` is already newest-first per §4.1, so no client-side
 * re-sort is applied.
 */
export function BidHistoryPanel({ auctionId }: { auctionId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: bidHistoryQueryKey(auctionId, 0, 20),
    queryFn: () => listBids(auctionId, 0, 20),
  });

  return (
    <section aria-label="Bid history" className="flex flex-col gap-2">
      <h2 className="text-sm font-semibold">Bid history</h2>
      {isLoading && <p className="text-sm text-gray-500">Loading…</p>}
      {isError && <p role="alert" className="text-sm text-red-600 dark:text-red-400">Could not load bid history.</p>}
      {data && (
        <ul className="flex flex-col gap-1 text-sm">
          {data.content.map((bid) => (
            <li key={bid.id} className="flex justify-between gap-2 border-b border-gray-200 py-1 dark:border-gray-800">
              <span>{displayHandle(bid.bidderId)}</span>
              <span>{bid.amount}</span>
              <span className="text-gray-500">{bid.status}</span>
              <span className="text-gray-500">{new Date(bid.createdAt).toLocaleTimeString()}</span>
            </li>
          ))}
          {data.content.length === 0 && <li className="text-gray-500">No bids yet.</li>}
        </ul>
      )}
    </section>
  );
}
