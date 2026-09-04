import { QueryClient, dehydrate, HydrationBoundary } from "@tanstack/react-query";
import { auctionsQueryKey, listAuctions } from "@/src/api/auctions";
import { categoriesQueryKey, getCategories } from "@/src/api/categories";
import { Browse } from "@/src/features/browse/Browse";
import { parseBrowseSearchParams } from "@/src/features/browse/searchParams";

/**
 * §12.2 Browse/Listings — listings change constantly (new bids, new auctions,
 * status transitions), so this route must never be frozen into the build at
 * `next build` time, matching the same reasoning §12.1's auction-detail page
 * already applies.
 */
export const dynamic = "force-dynamic";

type PageProps = {
  // Next 16: a Server Component's `searchParams` prop is a Promise (same as
  // `params` on the auction-detail page) — must be awaited.
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

/**
 * Server Component doing the initial `GET /auctions?...` fetch (query params
 * read from the URL's own search params, so a filtered/sorted Browse URL is
 * itself shareable and SSR's correctly — §12.2) plus `GET /categories` for the
 * filter chips, both prefetched into the same `QueryClient` and dehydrated into
 * a TanStack Query cache the client tree hydrates from (§5.2) — no separate
 * client-side re-fetch of what this already fetched.
 *
 * This file imports only `src/api/` (framework-agnostic) and the `Browse`
 * client-component tree — never anything from `src/ws/` or `src/state/`, even
 * transitively, per §7.1's SSR-safety rule.
 */
export default async function AuctionsPage({ searchParams }: PageProps) {
  const rawParams = await searchParams;
  const params = parseBrowseSearchParams(rawParams);

  const queryClient = new QueryClient();

  await Promise.all([
    queryClient.prefetchQuery({
      queryKey: auctionsQueryKey(params),
      queryFn: () => listAuctions(params),
    }),
    queryClient.prefetchQuery({
      queryKey: categoriesQueryKey,
      queryFn: getCategories,
    }),
  ]);

  const dehydratedState = dehydrate(queryClient);

  return (
    <HydrationBoundary state={dehydratedState}>
      <Browse />
    </HydrationBoundary>
  );
}
