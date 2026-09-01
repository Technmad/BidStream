import { fetchApi } from "./client";

/** `GET /categories` response item (§4.1) — verified against CategoryDtos.CategoryResponse. */
export type Category = {
  id: string;
  name: string;
  slug: string;
};

/**
 * TanStack Query key for the categories list. Exported (not inlined at each call
 * site) so the server-side fetch that seeds the dehydrated cache (§5.2) and every
 * client `useQuery` reading it agree on the exact same key — that agreement is
 * what makes hydration skip a redundant client-side refetch.
 */
export const categoriesQueryKey = ["categories"] as const;

/**
 * `GET /categories` — public, unauthenticated (§4.1's auth column: "–"). Used both
 * server-side (the SSR-proof page, §5.2) and client-side (future Sell/Browse filter
 * chips, §12.2/§12.4) — this is why it's a plain function rather than a hook.
 */
export async function getCategories(): Promise<Category[]> {
  return fetchApi<Category[]>("/categories", { method: "GET" });
}
