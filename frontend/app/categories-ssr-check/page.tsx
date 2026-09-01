import { QueryClient, dehydrate, HydrationBoundary } from "@tanstack/react-query";
import { categoriesQueryKey, getCategories, type Category } from "@/src/api/categories";
import { CategoriesListClient } from "./CategoriesListClient";

// This route's whole point is proving a live server-side fetch + hydration, so it
// must run per-request rather than being frozen into the build at `next build`
// time (when the backend may not even be running) — force-dynamic opts out of
// static generation for this route only.
export const dynamic = "force-dynamic";

/**
 * Phase 0's throwaway SSR-proof page (§18 Phase 0, §5.2).
 *
 * A Server Component doing a direct server-side `fetch` of `GET /categories`
 * (public, unauthenticated — §4.1), rendering the result straight into HTML
 * (real SSR, no client JS required to see it), and *also* seeding a TanStack
 * Query cache with the same data via `dehydrate`/`HydrationBoundary` so the
 * client-side tree underneath doesn't re-fetch what the server already got.
 *
 * This is the exact pattern §12.1 (Live Auction Room) and §12.2 (Browse) will
 * both depend on in later phases — proving it end-to-end here, against the
 * simplest possible real endpoint, is cheaper than discovering a hydration bug
 * after a real screen is built on top of it (§18's stated reasoning for Phase 0).
 */
export default async function CategoriesSsrCheckPage() {
  const queryClient = new QueryClient();

  let categories: Category[] = [];
  let fetchError: string | null = null;

  try {
    // Server-side fetch AND the query-cache seed happen from the same call via
    // prefetchQuery, so there is exactly one network request here, not two.
    await queryClient.prefetchQuery({
      queryKey: categoriesQueryKey,
      queryFn: getCategories,
    });
    categories = queryClient.getQueryData<Category[]>(categoriesQueryKey) ?? [];
  } catch (err) {
    // Backend unreachable, etc. — render a clear message instead of crashing the
    // server render. (Not part of the pattern under test; just keeps this page
    // usable as a build/lint check even when the backend isn't running.)
    fetchError = err instanceof Error ? err.message : "Failed to fetch categories.";
  }

  const dehydratedState = dehydrate(queryClient);

  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 py-16">
      <div>
        <h1 className="text-2xl font-semibold">SSR + HydrationBoundary check</h1>
        <p className="text-sm text-gray-500">
          Phase 0 throwaway page (FRONTEND-PDR.md §5.2, §18) — not a real screen.
        </p>
      </div>

      {fetchError ? (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          Could not reach the backend at build/request time: {fetchError}
        </p>
      ) : (
        <div className="flex flex-col gap-2 rounded border border-solid border-gray-400 p-4">
          <h2 className="text-sm font-semibold">
            Server-rendered (direct fetch, in this page&apos;s HTML)
          </h2>
          <ul className="list-disc pl-5 text-sm">
            {categories.map((category) => (
              <li key={category.id}>
                {category.name}{" "}
                <span className="text-gray-500">({category.slug})</span>
              </li>
            ))}
            {categories.length === 0 && <li className="text-gray-500">(none)</li>}
          </ul>
        </div>
      )}

      <HydrationBoundary state={dehydratedState}>
        <CategoriesListClient />
      </HydrationBoundary>
    </main>
  );
}
