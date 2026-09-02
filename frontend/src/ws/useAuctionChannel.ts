"use client";

import { useEffect, useRef } from "react";
import {
  auctionTopicDestination,
  getConnectionManager,
  NOTIFICATIONS_DESTINATION,
  type AuctionChannelMessage,
  type NotificationsChannelMessage,
} from "./connectionManager";

/**
 * The subscribe/unsubscribe hook components use (§7.1, §17) — part of the
 * connection manager's public surface, built now even though no screen consumes
 * it yet (Phase 2's job). Subscribes to `/topic/auctions/{auctionId}` for the
 * lifetime of the component (reference-counted by the manager, §7.1 point 2), and
 * forwards every message — including the synthetic `RESYNC` message (§7.4) — to
 * `onMessage`.
 *
 * `auctionId` may be `null`/`undefined` to represent "no auction selected yet"
 * (e.g. a route param not resolved yet) — the hook simply doesn't subscribe.
 * `onMessage` is read via a ref so callers can pass an inline arrow function
 * without re-subscribing on every render. The ref is synced in its own effect
 * (never written during render — React 19's `react-hooks/refs` rule flags that)
 * so it's always current by the time a message can actually arrive.
 */
export function useAuctionChannel(
  auctionId: string | null | undefined,
  onMessage: (message: AuctionChannelMessage) => void,
): void {
  const onMessageRef = useRef(onMessage);
  useEffect(() => {
    onMessageRef.current = onMessage;
  });

  useEffect(() => {
    if (!auctionId) return;
    const manager = getConnectionManager();
    const destination = auctionTopicDestination(auctionId);
    const unsubscribe = manager.subscribe(destination, (message) => {
      onMessageRef.current(message as AuctionChannelMessage);
    });
    return unsubscribe;
  }, [auctionId]);
}

/**
 * Subscribes to this user's own targeted notifications (`/user/queue/notifications`,
 * §4.2) — `OUTBID` and `BID_RESULT`. Separate from `useAuctionChannel` since this
 * destination isn't per-auction; consumers (typically `useReconciledAuction`,
 * filtering by `auctionId`/`correlationId` themselves) subscribe once per mount.
 */
export function useNotificationsChannel(
  onMessage: (message: NotificationsChannelMessage) => void,
): void {
  const onMessageRef = useRef(onMessage);
  useEffect(() => {
    onMessageRef.current = onMessage;
  });

  useEffect(() => {
    const manager = getConnectionManager();
    const unsubscribe = manager.subscribe(NOTIFICATIONS_DESTINATION, (message) => {
      onMessageRef.current(message as NotificationsChannelMessage);
    });
    return unsubscribe;
  }, []);
}
