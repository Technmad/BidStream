import { fetchApi } from "./client";

/**
 * `GET /auctions/{id}` response shape (§4.1) — verified against
 * `com.bidstream.adapter.in.rest.dto.AuctionDtos.AuctionResponse` (the running
 * backend source, not the backend PDR's prose): `id, sellerId, categoryId, title,
 * description, startingPrice, reservePrice, minIncrement, currentPrice,
 * currentWinnerId, status, startTime, endTime, antiSnipeSeconds, version`.
 *
 * This is Phase 1's only reason to touch `src/api/` (§7.4's resync rule needs a
 * plain REST fetch of an auction's current state) — the rest of the auction REST
 * surface (list, bids, leaderboard, create, etc.) is Phase 2+'s job.
 */
export type AuctionStatus =
  | "DRAFT"
  | "SCHEDULED"
  | "OPEN"
  | "EXTENDED"
  | "CLOSING"
  | "SOLD"
  | "UNSOLD"
  | "CANCELLED";

/** Terminal statuses per backend PDR §11.1 — used by the resync rule (§7.4). */
export const TERMINAL_AUCTION_STATUSES: ReadonlySet<AuctionStatus> = new Set([
  "SOLD",
  "UNSOLD",
  "CANCELLED",
]);

export function isTerminalStatus(status: AuctionStatus): boolean {
  return TERMINAL_AUCTION_STATUSES.has(status);
}

export type AuctionResponse = {
  id: string;
  sellerId: string;
  categoryId: string | null;
  title: string;
  description: string | null;
  startingPrice: string;
  reservePrice: string | null;
  minIncrement: string;
  currentPrice: string;
  currentWinnerId: string | null;
  status: AuctionStatus;
  startTime: string;
  endTime: string;
  antiSnipeSeconds: number;
  version: number;
};

/**
 * `GET /auctions/{id}` — public, unauthenticated (§4.1). Used both for the SSR
 * initial paint (Phase 2) and, here in Phase 1, as the connection manager's
 * resync fetch on reconnect (§7.4) — a plain function rather than a hook so both
 * callers can use it.
 */
export async function getAuction(auctionId: string): Promise<AuctionResponse> {
  return fetchApi<AuctionResponse>(`/auctions/${auctionId}`, { method: "GET" });
}

/**
 * TanStack Query key for a single auction's detail (§17's `auctionQueryKey`
 * requirement) — mirrors `categoriesQueryKey`'s pattern (`src/api/categories.ts`)
 * so the Server Component's `prefetchQuery` and the client `useQuery` reading it
 * agree on the exact same key (§5.2's "no separate re-fetch" requirement).
 */
export function auctionQueryKey(auctionId: string) {
  return ["auction", auctionId] as const;
}

/**
 * `GET /auctions/{id}/bids` (§4.1) — public, paginated Spring `Page`. Only the
 * fields the bid-history panel actually needs are typed (§12.1's data source) —
 * the rest of Spring's `Page` envelope (`totalPages`, `number`, `size`, etc.) is
 * left untyped on purpose per the task brief rather than modeled in full.
 */
export type BidHistoryEntry = {
  id: string;
  auctionId: string;
  bidderId: string;
  amount: string;
  type: string;
  status: string;
  createdAt: string;
};

export type BidHistoryPage = {
  content: BidHistoryEntry[];
  totalElements: number;
};

export function bidHistoryQueryKey(auctionId: string, page: number, size: number) {
  return ["auction", auctionId, "bids", page, size] as const;
}

export async function listBids(
  auctionId: string,
  page: number = 0,
  size: number = 20,
): Promise<BidHistoryPage> {
  return fetchApi<BidHistoryPage>(
    `/auctions/${auctionId}/bids?page=${page}&size=${size}`,
    { method: "GET" },
  );
}

/** `GET /auctions/{id}/leaderboard?limit=` (§4.1) — public, top-N `{bidderId, amount}`. */
export type LeaderboardEntry = {
  bidderId: string;
  amount: string;
};

export function leaderboardQueryKey(auctionId: string, limit: number) {
  return ["auction", auctionId, "leaderboard", limit] as const;
}

export async function getLeaderboard(
  auctionId: string,
  limit: number = 10,
): Promise<LeaderboardEntry[]> {
  return fetchApi<LeaderboardEntry[]>(
    `/auctions/${auctionId}/leaderboard?limit=${limit}`,
    { method: "GET" },
  );
}

/** `202 {bidId, status:"PENDING", correlationId}` — §4.1's default (never `?wait=true`, §4.3/§8.4). */
export type PlaceBidResponse = {
  bidId: string;
  status: string;
  correlationId: string;
};

/**
 * `POST /auctions/{id}/bids` (auth required, §4.1). Sets a client-generated
 * `Idempotency-Key` header via `crypto.randomUUID()` — required by the backend
 * contract, not optional. Body is a decimal string, never a `Number()`, per §4.1's
 * "send as a decimal string to avoid float formatting surprises."
 *
 * Deliberately never appends `?wait=true` (§4.1, §4.3, §8.4).
 */
export async function placeBid(
  auctionId: string,
  amount: string,
  accessToken: string,
): Promise<PlaceBidResponse> {
  return fetchApi<PlaceBidResponse>(`/auctions/${auctionId}/bids`, {
    method: "POST",
    body: { amount },
    accessToken,
    headers: { "Idempotency-Key": crypto.randomUUID() },
  });
}

/**
 * `GET /auctions` (§4.1, §12.2) — public, unauthenticated, paginated Spring
 * `Page<AuctionResponse>`. Typed loosely for the envelope fields, matching
 * `BidHistoryPage`'s convention above (only what Browse actually needs —
 * `content`, `totalElements`, `totalPages`, `number` — rather than modeling
 * every field Spring's generic `Page` serialization happens to emit).
 */
export type AuctionsPage = {
  content: AuctionResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

export type ListAuctionsParams = {
  status?: AuctionStatus;
  category?: string;
  sellerId?: string;
  q?: string;
  page?: number;
  size?: number;
  /** Spring `Pageable`'s generic field-based sort, e.g. `"endTime,asc"` for "Ending Soon" (§12.2) — there is no `sort=endingSoon` alias. */
  sort?: string;
};

/**
 * TanStack Query key for a Browse listing, incorporating every filter/sort/page
 * param so distinct filtered views cache independently — mirrors
 * `categoriesQueryKey`'s naming convention (`src/api/categories.ts`) and
 * `bidHistoryQueryKey`'s "params in the key" shape above.
 */
export function auctionsQueryKey(params: ListAuctionsParams = {}) {
  return [
    "auctions",
    params.status ?? null,
    params.category ?? null,
    params.sellerId ?? null,
    params.q ?? null,
    params.page ?? 0,
    params.size ?? 20,
    params.sort ?? null,
  ] as const;
}

/**
 * `GET /auctions?status=&category=&sellerId=&q=&page=&size=&sort=` (§4.1, §12.2) —
 * builds a query string from whichever params are actually present, omitting
 * undefined ones and never sending an empty-string param.
 */
export async function listAuctions(params: ListAuctionsParams = {}): Promise<AuctionsPage> {
  const search = new URLSearchParams();
  if (params.status) search.set("status", params.status);
  if (params.category) search.set("category", params.category);
  if (params.sellerId) search.set("sellerId", params.sellerId);
  if (params.q) search.set("q", params.q);
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  if (params.sort) search.set("sort", params.sort);

  const qs = search.toString();
  return fetchApi<AuctionsPage>(`/auctions${qs ? `?${qs}` : ""}`, { method: "GET" });
}

/** `POST /auctions/{id}/auto-bid` (auth) → `AutoBidResponse`. */
export type AutoBidResponse = {
  id: string;
  auctionId: string;
  bidderId: string;
  maxAmount: string;
  active: boolean;
  createdAt: string;
};

export async function setAutoBid(
  auctionId: string,
  maxAmount: string,
  accessToken: string,
): Promise<AutoBidResponse> {
  return fetchApi<AutoBidResponse>(`/auctions/${auctionId}/auto-bid`, {
    method: "POST",
    body: { maxAmount },
    accessToken,
  });
}

/** `DELETE /auctions/{id}/auto-bid` (auth) → `204`. */
export async function cancelAutoBid(auctionId: string, accessToken: string): Promise<void> {
  await fetchApi<void>(`/auctions/${auctionId}/auto-bid`, {
    method: "DELETE",
    accessToken,
  });
}
