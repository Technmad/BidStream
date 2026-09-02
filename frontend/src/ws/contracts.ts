/**
 * Typed WebSocket message contracts — FRONTEND-PDR.md §4.2, copied verbatim.
 * One source of truth (§17): every place that parses an inbound STOMP frame body
 * imports from here, never redefines a shape inline.
 *
 * Verification status (§4.2's own note): every field below except `version` was
 * read directly from running backend code (v1.4) and is fact. `version` reflects
 * backend PDR v1.5's specification, tracked as "the contract the frontend is built
 * against" per the PDR's own note — re-confirm against a running deploy's actual
 * `TickBroadcaster`/`PriceUpdateMessage` output before this phase is fully closed
 * out end-to-end (that verification is a backend-adjacent follow-up, not something
 * this frontend-only phase can perform itself).
 */

/** `/topic/auctions/{id}` — broadcast, no auth needed to receive once subscribed. */
export type PriceUpdate = {
  type: "PRICE_UPDATE";
  auctionId: string;
  /** Decimal string — never Number() this for logic, only for display formatting. */
  price: string;
  winnerId: string | null;
  /** ISO instant. */
  endTime: string;
  /**
   * auctions.version — the ONLY field that establishes ordering between two
   * confirmed states (§8.3). `serverNow` is a send-time, not a state counter, and
   * must never be used to decide which of two states is newer.
   */
  version: number;
  /** ISO instant — the clock-offset module's input (§10). */
  serverNow: string;
};

export type AuctionExtended = {
  type: "AUCTION_EXTENDED";
  auctionId: string;
  newEndTime: string;
  version: number;
  serverNow: string;
};

export type AuctionEnded = {
  type: "AUCTION_ENDED";
  auctionId: string;
  outcome: "SOLD" | "UNSOLD";
  winnerId: string | null;
  finalPrice: string | null;
  version: number;
  serverNow: string;
};

/** Union of every message delivered on `/topic/auctions/{id}`. */
export type AuctionTopicMessage = PriceUpdate | AuctionExtended | AuctionEnded;

/** `/user/queue/notifications` — targeted, per-user. */
export type Outbid = {
  type: "OUTBID";
  auctionId: string;
  /** Note: `newPrice` here, NOT `price`. */
  newPrice: string;
};

export type BidResult = {
  type: "BID_RESULT";
  correlationId: string;
  /**
   * NOT two separate fields. One string: either the literal "ACCEPTED", or
   * "REJECTED:<REASON>" — e.g. "REJECTED:BELOW_MIN_INCREMENT". Parse with
   * `parseBidResult` below, in exactly one place (§8.3) — never inline at a call site.
   */
  status: string;
};

/** Union of every message delivered on `/user/queue/notifications`. */
export type NotificationsQueueMessage = Outbid | BidResult;

export type WsMessage = AuctionTopicMessage | NotificationsQueueMessage;

/** Parsed result of `BidResult.status` (§8.3, §4.2). */
export type ParsedBidResult =
  | { outcome: "ACCEPTED" }
  | { outcome: "REJECTED"; reason: string };

/**
 * The one place `BID_RESULT`'s `status` string is ever parsed (§8.3, §4.2). Every
 * consumer of a `BidResult` message must go through this helper rather than
 * re-splitting the string ad hoc.
 */
export function parseBidResult(status: string): ParsedBidResult {
  if (status === "ACCEPTED") {
    return { outcome: "ACCEPTED" };
  }
  const [prefix, ...rest] = status.split(":");
  if (prefix === "REJECTED") {
    return { outcome: "REJECTED", reason: rest.join(":") };
  }
  // Defensive fallback for an unrecognized shape — treat as a rejection with the
  // raw status as the reason rather than throwing, since a malformed/unexpected
  // status string is a backend-contract surprise this module shouldn't crash on.
  return { outcome: "REJECTED", reason: status };
}
