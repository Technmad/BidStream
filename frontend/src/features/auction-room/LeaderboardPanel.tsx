"use client";

import { useQuery } from "@tanstack/react-query";
import { getLeaderboard, leaderboardQueryKey } from "@/src/api/auctions";
import { displayHandle } from "@/src/lib/displayHandle";

/** §12.1's optional top-bidders widget — `GET /auctions/{id}/leaderboard?limit=`. */
export function LeaderboardPanel({ auctionId, limit = 10 }: { auctionId: string; limit?: number }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: leaderboardQueryKey(auctionId, limit),
    queryFn: () => getLeaderboard(auctionId, limit),
  });

  return (
    <section aria-label="Leaderboard" className="flex flex-col gap-2">
      <h2 className="text-sm font-semibold">Top bidders</h2>
      {isLoading && <p className="text-sm text-gray-500">Loading…</p>}
      {isError && <p role="alert" className="text-sm text-red-600 dark:text-red-400">Could not load leaderboard.</p>}
      {data && (
        <ol className="flex flex-col gap-1 text-sm">
          {data.map((entry, i) => (
            <li key={`${entry.bidderId}-${i}`} className="flex justify-between gap-2">
              <span>
                {i + 1}. {displayHandle(entry.bidderId)}
              </span>
              <span>{entry.amount}</span>
            </li>
          ))}
          {data.length === 0 && <li className="text-gray-500">No bids yet.</li>}
        </ol>
      )}
    </section>
  );
}
