"use client";

import { useQuery } from "@tanstack/react-query";
import { categoriesQueryKey, getCategories } from "@/src/api/categories";

/**
 * Client half of the Phase 0 SSR-proof (§5.2, §18 Phase 0's deliverable).
 *
 * Reads the *identical* query key the Server Component below dehydrated into the
 * cache. If the hydration wiring is correct, `useQuery` here resolves instantly
 * from that dehydrated data with no network request — open the browser Network
 * tab while loading this page: there should be no client-side `GET /categories`.
 * If the boundary is wired wrong, this refetches instead, which is exactly the
 * failure mode this page exists to catch before any real screen depends on the
 * same pattern (§12.1's Live Auction Room, §12.2's Browse).
 */
export function CategoriesListClient() {
  const { data, isFetching } = useQuery({
    queryKey: categoriesQueryKey,
    queryFn: getCategories,
  });

  return (
    <div className="flex flex-col gap-2 rounded border border-dashed border-gray-400 p-4">
      <h2 className="text-sm font-semibold">Client-side render (useQuery, same key)</h2>
      <p className="text-xs text-gray-500">
        isFetching: <code>{String(isFetching)}</code> — should be{" "}
        <code>false</code> on first paint if hydration worked.
      </p>
      <ul className="list-disc pl-5 text-sm">
        {data?.map((category) => (
          <li key={category.id}>
            {category.name}{" "}
            <span className="text-gray-500">({category.slug})</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
