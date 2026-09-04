import type { AuctionStatus, ListAuctionsParams } from "@/src/api/auctions";

const VALID_STATUSES: ReadonlySet<string> = new Set([
  "DRAFT",
  "SCHEDULED",
  "OPEN",
  "EXTENDED",
  "CLOSING",
  "SOLD",
  "UNSOLD",
  "CANCELLED",
]);

export const BROWSE_PAGE_SIZE = 12;

/**
 * Maps raw URL search params (from either the Server Component's `searchParams`
 * prop or the client's `useSearchParams()`) onto `listAuctions`'s params — one
 * shared function so the server's `prefetchQuery` and the client's `useQuery`
 * agree on exactly the same params (and therefore the same `auctionsQueryKey`),
 * satisfying §5.2's "no flash-then-replace" / no redundant client refetch rule.
 */
export function parseBrowseSearchParams(
  raw: Record<string, string | string[] | undefined> | URLSearchParams,
): ListAuctionsParams {
  const get = (key: string): string | undefined => {
    if (raw instanceof URLSearchParams) return raw.get(key) ?? undefined;
    const value = raw[key];
    return Array.isArray(value) ? value[0] : value;
  };

  const status = get("status");
  const category = get("category");
  const sellerId = get("sellerId");
  const q = get("q");
  const sort = get("sort");
  const pageRaw = get("page");
  const page = pageRaw !== undefined ? Number.parseInt(pageRaw, 10) : undefined;

  return {
    status: status && VALID_STATUSES.has(status) ? (status as AuctionStatus) : undefined,
    category: category || undefined,
    sellerId: sellerId || undefined,
    q: q || undefined,
    sort: sort || undefined,
    page: page !== undefined && !Number.isNaN(page) && page >= 0 ? page : undefined,
    size: BROWSE_PAGE_SIZE,
  };
}
