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
