import { QueryClient, dehydrate, HydrationBoundary } from "@tanstack/react-query";
import { notFound } from "next/navigation";
import { auctionQueryKey, getAuction } from "@/src/api/auctions";
import { ApiError } from "@/src/api/client";
import { AuctionRoom } from "@/src/features/auction-room/AuctionRoom";

/**
 * §12.1 Live Auction Room — Server Component shell. Auction state changes
 * constantly (live price, countdown, status), so this route must never be
 * frozen into the build at `next build` time (§18 Phase 0's SSR-proof page
 * established the pattern this page reuses; §12.1 requires it here for real).
 */
export const dynamic = "force-dynamic";

type PageProps = {
  params: Promise<{ id: string }>;
};

/**
 * Server Component doing the initial `GET /auctions/{id}` fetch (public,
 * unauthenticated — §4.1) and dehydrating it into a TanStack Query cache the
 * client tree hydrates from (§5.2) — this fetched state *is* the first confirmed
 * state fed into `useReconciledAuction` (§8.3, §12.1), not a second, independent
 * render. A 404 from the backend maps to Next's own `notFound()` (§13's REST 404
 * row: "Not found state, not a crash").
 *
 * This file imports only `src/api/` (framework-agnostic, no `window`/`ws`/`state`
 * access — see `src/api/client.ts`) and `next/navigation` — never anything from
 * `src/ws/` or `src/state/`, even transitively, per §7.1's SSR-safety rule.
 */
export default async function AuctionRoomPage({ params }: PageProps) {
  const { id } = await params;
  const queryClient = new QueryClient();

  try {
    await queryClient.prefetchQuery({
      queryKey: auctionQueryKey(id),
      queryFn: () => getAuction(id),
    });
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      notFound();
    }
    throw err;
  }

  const dehydratedState = dehydrate(queryClient);

  return (
    <HydrationBoundary state={dehydratedState}>
      <AuctionRoom auctionId={id} />
    </HydrationBoundary>
  );
}
