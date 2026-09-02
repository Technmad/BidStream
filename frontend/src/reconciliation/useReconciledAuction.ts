"use client";

import { useCallback, useState } from "react";
import type { AuctionStatus } from "@/src/api/auctions";
import { useConnectionStore, type ConnectionState } from "@/src/state/connectionStore";
import { usePendingBidsStore, type PendingBid, PENDING_BID_TIMEOUT_MS } from "@/src/state/pendingBids";
import type { AuctionChannelMessage, ResyncMessage } from "@/src/ws/connectionManager";
import { useAuctionChannel, useNotificationsChannel } from "@/src/ws/useAuctionChannel";
import {
  parseBidResult,
  type AuctionEnded,
  type AuctionExtended,
  type AuctionTopicMessage,
  type BidResult,
  type NotificationsQueueMessage,
  type Outbid,
  type ParsedBidResult,
  type PriceUpdate,
} from "@/src/ws/contracts";

/**
 * §8.2's `ReconciledAuctionView.confirmed` shape, extended with an optional
 * `status`. **Deviation from the PDR's literal §8.2 sample, stated explicitly:**
 * the base type has no field that can express "this auction has ended" — needed
 * to satisfy §7.4's requirement ("if the resync response carries a terminal
 * status, end the auction in the UI immediately... regardless of whether
 * AUCTION_ENDED was ever seen") and `AUCTION_ENDED`'s own `outcome`. `status` is
 * `undefined` for an ordinary `PRICE_UPDATE`/`AUCTION_EXTENDED` (which don't carry
 * one) and is only ever set to a terminal `AuctionStatus` by `AUCTION_ENDED` or a
 * resync response — `isEnded` below is exactly "is `status` terminal."
 */
export type NormalizedConfirmedState = {
  price: string;
  winnerId: string | null;
  endTime: string;
  version: number;
  status?: AuctionStatus;
};

const TERMINAL_STATUSES: ReadonlySet<AuctionStatus> = new Set(["SOLD", "UNSOLD", "CANCELLED"]);

function isTerminal(status: AuctionStatus | undefined): boolean {
  return status !== undefined && TERMINAL_STATUSES.has(status);
}

type ConfirmedMessage = AuctionTopicMessage | ResyncMessage;

/**
 * Builds the candidate confirmed state a given message represents, carrying
 * forward whatever `current` doesn't override (e.g. `AUCTION_EXTENDED` only
 * carries a new `endTime`+`version`, not price/winner). Exported for direct unit
 * testing (§14) alongside `applyIncomingConfirmed`.
 */
export function candidateFromMessage(
  current: NormalizedConfirmedState | null,
  message: ConfirmedMessage,
): NormalizedConfirmedState {
  switch (message.type) {
    case "PRICE_UPDATE": {
      const m = message as PriceUpdate;
      return { price: m.price, winnerId: m.winnerId, endTime: m.endTime, version: m.version };
    }
    case "AUCTION_EXTENDED": {
      const m = message as AuctionExtended;
      return {
        price: current?.price ?? "0",
        winnerId: current?.winnerId ?? null,
        endTime: m.newEndTime,
        version: m.version,
        status: current?.status,
      };
    }
    case "AUCTION_ENDED": {
      const m = message as AuctionEnded;
      return {
        price: m.finalPrice ?? current?.price ?? "0",
        winnerId: m.winnerId,
        endTime: current?.endTime ?? m.serverNow,
        version: m.version,
        status: m.outcome, // "SOLD" | "UNSOLD" — both terminal AuctionStatus values
      };
    }
    case "RESYNC": {
      const m = message as ResyncMessage;
      return { price: m.price, winnerId: m.winnerId, endTime: m.endTime, version: m.version, status: m.status };
    }
  }
}

/**
 * §8.3 steps 1-2 — the version-monotonic merge, as a pure function:
 * "Apply it, and update `displayedVersion`, only if the incoming `version` is
 * strictly greater. Otherwise discard the incoming state entirely and keep
 * what's already displayed." `current === null` (nothing displayed yet) always
 * applies the incoming state, seeding `displayedVersion`.
 */
export function applyIncomingConfirmed(
  current: NormalizedConfirmedState | null,
  message: ConfirmedMessage,
): NormalizedConfirmedState {
  const candidate = candidateFromMessage(current, message);
  if (current === null || candidate.version > current.version) {
    return candidate;
  }
  return current;
}

export type ReconciliationEvent =
  | { kind: "bidResult"; correlationId: string; result: ParsedBidResult }
  | { kind: "outbid"; auctionId: string; newPrice: string };

export type ReconciliationState = {
  confirmed: NormalizedConfirmedState | null;
  pending: PendingBid | null;
};

export type IncomingMessage = ConfirmedMessage | BidResult | Outbid;

/**
 * The full §8.3 merge algorithm as one pure function of
 * `(confirmed, pending, incoming message) -> next state` (§14's unit-test target),
 * no React or network involved:
 *
 * - step 2 (version-monotonic confirmed-state merge) for `PRICE_UPDATE` /
 *   `AUCTION_EXTENDED` / `AUCTION_ENDED` / `RESYNC`.
 * - step 4 (`BID_RESULT`): parses `status` via `parseBidResult`, clears `pending`
 *   only if its `correlationId` matches, and never touches `confirmed` — the next
 *   qualifying `PRICE_UPDATE` does that (step 2), not this message.
 * - step 5 (`OUTBID`): clears `pending` immediately if it belongs to the same
 *   auction, and never touches `confirmed` — the confirmed price/winner change it
 *   describes arrives separately through step 2.
 *
 * (§8.3 step 3 — attaching `pending` to the view — is the caller's job, not this
 * function's: `pending` is an input here, not something this function invents.)
 */
export function reconcile(
  state: ReconciliationState,
  message: IncomingMessage,
): { state: ReconciliationState; event: ReconciliationEvent | null } {
  switch (message.type) {
    case "PRICE_UPDATE":
    case "AUCTION_EXTENDED":
    case "AUCTION_ENDED":
    case "RESYNC":
      return {
        state: { ...state, confirmed: applyIncomingConfirmed(state.confirmed, message) },
        event: null,
      };
    case "BID_RESULT": {
      const result = parseBidResult(message.status);
      const stillPending = state.pending?.correlationId === message.correlationId;
      return {
        state: { ...state, pending: stillPending ? null : state.pending },
        event: { kind: "bidResult", correlationId: message.correlationId, result },
      };
    }
    case "OUTBID": {
      const matches = state.pending?.auctionId === message.auctionId;
      return {
        state: { ...state, pending: matches ? null : state.pending },
        event: { kind: "outbid", auctionId: message.auctionId, newPrice: message.newPrice },
      };
    }
    default:
      return { state, event: null };
  }
}

export type PendingBidDisplayStatus = "none" | "pending" | "still-processing";

export type ReconciledAuctionView = {
  /** §8.2's `confirmed` shape, extended per the note above. `null` until the first confirmed state arrives. */
  confirmed: NormalizedConfirmedState | null;
  /** This caller's own in-flight bid, if any (§8.3 step 3). */
  myPendingBid: PendingBid | null;
  /** §8.4 — "none" | "confirming…" (pending) | "still processing" (10s elapsed, not a failure). */
  pendingBidStatus: PendingBidDisplayStatus;
  /** Surfaced so the UI can show it (§9). */
  connectionState: ConnectionState;
  /** True once `confirmed.status` is a terminal `AuctionStatus` (§7.4). */
  isEnded: boolean;
  /** The most recent `BID_RESULT` this hook has observed for this auction, for a toast (not part of confirmed state — §8.3 step 4). */
  lastBidResult: { correlationId: string; result: ParsedBidResult } | null;
  /** The most recent `OUTBID` notice, for a toast (§8.3 step 5). */
  lastOutbid: { newPrice: string } | null;
};

/**
 * The one merge point (§6, §8) — every screen reads auction state only through
 * this hook, never the raw WebSocket state or the raw REST cache directly.
 *
 * `initialConfirmed` is the first confirmed state (typically an SSR'd/TanStack
 * Query `GET /auctions/{id}` result in Phase 2, per §5.2's "no flash-then-replace"
 * requirement) — pass `null` if nothing has been fetched yet.
 */
export function useReconciledAuction(
  auctionId: string,
  initialConfirmed: NormalizedConfirmedState | null,
): ReconciledAuctionView {
  const [confirmed, setConfirmed] = useState<NormalizedConfirmedState | null>(initialConfirmed);
  const [lastBidResult, setLastBidResult] = useState<ReconciledAuctionView["lastBidResult"]>(null);
  const [lastOutbid, setLastOutbid] = useState<ReconciledAuctionView["lastOutbid"]>(null);

  const connectionState = useConnectionStore((s) => s.status);
  const myPendingBid = usePendingBidsStore((s) => s.getPendingBidForAuction(auctionId));
  const isTimedOut = usePendingBidsStore((s) =>
    myPendingBid ? s.isTimedOut(myPendingBid.correlationId) : false,
  );
  const removePendingBid = usePendingBidsStore((s) => s.removePendingBid);

  const handleAuctionMessage = useCallback(
    (message: AuctionChannelMessage) => {
      setConfirmed((prev) => applyIncomingConfirmed(prev, message));
    },
    [],
  );

  const handleNotification = useCallback(
    (message: NotificationsQueueMessage) => {
      if (message.type === "BID_RESULT") {
        const currentPending = usePendingBidsStore.getState().getPendingBidForAuction(auctionId);
        if (!currentPending || currentPending.correlationId !== message.correlationId) return;
        setLastBidResult({ correlationId: message.correlationId, result: parseBidResult(message.status) });
        removePendingBid(message.correlationId);
      } else if (message.type === "OUTBID") {
        if (message.auctionId !== auctionId) return;
        const currentPending = usePendingBidsStore.getState().getPendingBidForAuction(auctionId);
        if (currentPending) removePendingBid(currentPending.correlationId);
        setLastOutbid({ newPrice: message.newPrice });
      }
    },
    [auctionId, removePendingBid],
  );

  useAuctionChannel(auctionId, handleAuctionMessage);
  useNotificationsChannel(handleNotification);

  const pendingBidStatus: PendingBidDisplayStatus = !myPendingBid
    ? "none"
    : isTimedOut
      ? "still-processing"
      : "pending";

  return {
    confirmed,
    myPendingBid,
    pendingBidStatus,
    connectionState,
    isEnded: isTerminal(confirmed?.status),
    lastBidResult,
    lastOutbid,
  };
}

export { PENDING_BID_TIMEOUT_MS };
