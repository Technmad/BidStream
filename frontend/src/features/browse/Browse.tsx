"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { auctionsQueryKey, listAuctions } from "@/src/api/auctions";
import { AuctionCard } from "./AuctionCard";
import { FilterBar } from "./FilterBar";
import { Pagination } from "./Pagination";
import { parseBrowseSearchParams } from "./searchParams";

/**
 * §12.2 Browse orchestrator — a Client Component consumed by `app/auctions/page.tsx`.
 * Reads filter/sort/page state from the URL (via `useSearchParams`, so a filter
 * change elsewhere updates the URL and this component just re-derives its query
 * from it), and fetches via `useQuery` against the exact same `auctionsQueryKey`
 * the server prefetched — hydration-safe, no redundant refetch on first paint.
 */
export function Browse() {
  const searchParams = useSearchParams();
  const params = parseBrowseSearchParams(searchParams);

  const { data, isLoading, isError } = useQuery({
    queryKey: auctionsQueryKey(params),
    queryFn: () => listAuctions(params),
  });

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-10">
      <h1 className="text-2xl font-semibold">Browse auctions</h1>

      <FilterBar />

      {isError && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          Could not load auctions. Please try again.
        </p>
      )}

      {isLoading && !data && <p className="text-sm text-gray-500">Loading…</p>}

      {data && data.content.length === 0 && (
        <p className="text-sm text-gray-500">No auctions match these filters.</p>
      )}

      {data && data.content.length > 0 && (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {data.content.map((auction) => (
            <AuctionCard key={auction.id} auction={auction} />
          ))}
        </ul>
      )}

      {data && <Pagination page={data.number} totalPages={data.totalPages} />}
    </main>
  );
}
