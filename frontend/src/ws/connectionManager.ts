"use client";

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";
import { getAuction, type AuctionResponse } from "@/src/api/auctions";
import { getValidAccessToken } from "@/src/features/auth/tokenStore";
import { useClockOffsetStore } from "@/src/state/clockOffset";
import { useConnectionStore } from "@/src/state/connectionStore";
import { computeBackoffDelay } from "./backoff";
import type { AuctionTopicMessage, NotificationsQueueMessage } from "./contracts";
import { WS_BASE_URL, toBrokerUrl } from "./wsConfig";

/**
 * §7.4's resync rule needs a way to hand a REST-fetched auction state to whatever
 * is listening on `/topic/auctions/{id}` as if it were a frame — not part of the
 * backend's own WS contract (§4.2), so it isn't in `contracts.ts`, which stays the
 * verified backend contract only. `useReconciledAuction` treats this exactly like
 * any other confirmed-state input (§8.3 step 2 explicitly includes "a REST
 * response" alongside WS frames).
 */
export type ResyncMessage = {
  type: "RESYNC";
  auctionId: string;
  price: string;
  winnerId: string | null;
  endTime: string;
  version: number;
  /** Present so §7.4's "terminal REST status overrides a missed AUCTION_ENDED" rule is expressible. */
  status: AuctionResponse["status"];
};

export type AuctionChannelMessage = AuctionTopicMessage | ResyncMessage;
export type NotificationsChannelMessage = NotificationsQueueMessage;

type Destination = string;
type RawMessage = AuctionChannelMessage | NotificationsChannelMessage;
type Callback = (message: RawMessage) => void;

export const NOTIFICATIONS_DESTINATION = "/user/queue/notifications";

export function auctionTopicDestination(auctionId: string): Destination {
  return `/topic/auctions/${auctionId}`;
}

/** Extracts `{id}` from `/topic/auctions/{id}`, or `null` for any other destination. */
function auctionIdFromDestination(destination: Destination): string | null {
  const match = destination.match(/^\/topic\/auctions\/(.+)$/);
  return match ? match[1] : null;
}

type QueuedFrame = { destination: Destination; message: RawMessage };

const HIDDEN_STALE_TIMEOUT_MS = 60_000;

/**
 * The WebSocket connection manager (§7) — a single module-scope class, instantiated
 * once, lazily, outside React's render tree (§7.1 point 0). Never construct this or
 * call any of its methods during server render; `getConnectionManager()` is the
 * only accessor and it throws if called where `window` doesn't exist.
 */
export class ConnectionManager {
  /**
   * Optional test-only override — when provided, passed to `@stomp/stompjs`'s
   * `Client` as `webSocketFactory`, which it prefers over `brokerURL` (see
   * `client.ts`'s `activate()`: `webSocketFactory` wins when both are set). This
   * is how `src/test/mockBroker.ts` (§14.3) attaches a fake in-memory broker
   * without the manager needing to know it's under test — production code never
   * passes this constructor argument, so `getConnectionManager()` always gets a
   * real `WebSocket` against `WS_BASE_URL`.
   */
  constructor(private readonly webSocketFactory?: () => WebSocket) {}

  private client: Client | null = null;
  /** destination -> set of component callbacks (§7.1 point 2, reference-counted). */
  private readonly subscriptions = new Map<Destination, Set<Callback>>();
  /** destination -> the live STOMP subscription, present only while actually connected. */
  private readonly activeStompSubs = new Map<Destination, StompSubscription>();
  private readonly frameQueue: QueuedFrame[] = [];
  private rafHandle: number | null = null;
  private backoffAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private hiddenTimer: ReturnType<typeof setTimeout> | null = null;
  private visibilityHandler: (() => void) | null = null;
  private resyncInFlight = false;

  // --- Lifecycle -----------------------------------------------------------

  /** Idempotent — a second call while already connecting/connected is a no-op. */
  connect(): void {
    if (this.client) return;

    const client = new Client({
      brokerURL: toBrokerUrl(WS_BASE_URL),
      webSocketFactory: this.webSocketFactory,
      reconnectDelay: 0, // manual backoff (§7.3) — stomp.js's own auto-reconnect is disabled
      beforeConnect: async () => {
        const token = await getValidAccessToken(); // §7.2 — refresh first if stale
        client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => {
        this.backoffAttempt = 0; // §7.3 — reset backoff to 1s on any successful connect
        void this.handleConnected();
      },
      onWebSocketClose: () => {
        this.handleDisconnected();
      },
      onStompError: () => {
        this.handleDisconnected();
      },
    });

    this.client = client;
    this.attachVisibilityHandling();
    client.activate();
  }

  /** Full teardown — cancels timers, detaches listeners, deactivates the socket. */
  disconnect(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.hiddenTimer) clearTimeout(this.hiddenTimer);
    if (this.rafHandle !== null && typeof cancelAnimationFrame !== "undefined") {
      cancelAnimationFrame(this.rafHandle);
    }
    this.detachVisibilityHandling();
    this.activeStompSubs.clear();
    void this.client?.deactivate();
    this.client = null;
  }

  private async handleConnected(): Promise<void> {
    // §7.4: on every (re)connect, refetch every subscribed auction's current state
    // via REST, and only then resubscribe — never resume the live stream first.
    await this.resyncAll();
    this.resubscribeAll();
    useConnectionStore.getState().dispatch("connect_success");
  }

  private handleDisconnected(): void {
    this.activeStompSubs.clear();
    const status = useConnectionStore.getState().status;
    useConnectionStore.getState().dispatch(status === "connecting" ? "connect_failed" : "socket_closed");
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return; // already scheduled
    const delay = computeBackoffDelay(this.backoffAttempt);
    this.backoffAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      useConnectionStore.getState().dispatch("backoff_elapsed");
      // stomp.js's own `activate()` is a no-op while its internal `active` flag is
      // still true (which it is — we never called `deactivate()`, since we manage
      // reconnection ourselves rather than stomp.js's built-in reconnectDelay
      // loop). `deactivate()` here resolves immediately (the socket is already
      // closed, so it takes the synchronous INACTIVE branch) and just flips that
      // internal flag so the following `activate()` actually opens a new socket.
      void this.client?.deactivate().then(() => {
        this.client?.activate();
      });
    }, delay);
  }

  // --- Subscription registry (§7.1 point 2) ---------------------------------

  /**
   * Registers `callback` for `destination`. The manager sends the STOMP `SUBSCRIBE`
   * frame only the first time any caller asks for a given destination, and
   * `UNSUBSCRIBE` only when the last one stops asking — reference-counted, not
   * per-component. Returns an unsubscribe function.
   */
  subscribe(destination: Destination, callback: Callback): () => void {
    let callbacks = this.subscriptions.get(destination);
    const isNewDestination = !callbacks;
    if (!callbacks) {
      callbacks = new Set();
      this.subscriptions.set(destination, callbacks);
    }
    callbacks.add(callback);

    if (isNewDestination && this.client?.connected) {
      this.sendSubscribe(destination);
    }

    return () => {
      const set = this.subscriptions.get(destination);
      if (!set) return;
      set.delete(callback);
      if (set.size === 0) {
        this.subscriptions.delete(destination);
        const stompSub = this.activeStompSubs.get(destination);
        if (stompSub) {
          stompSub.unsubscribe();
          this.activeStompSubs.delete(destination);
        }
      }
    };
  }

  private sendSubscribe(destination: Destination): void {
    if (!this.client?.connected || this.activeStompSubs.has(destination)) return;
    const stompSub = this.client.subscribe(destination, (frame: IMessage) => {
      this.onFrame(destination, frame);
    });
    this.activeStompSubs.set(destination, stompSub);
  }

  private resubscribeAll(): void {
    for (const destination of this.subscriptions.keys()) {
      this.sendSubscribe(destination);
    }
  }

  // --- Resync (§7.4) ---------------------------------------------------------

  private subscribedAuctionIds(): string[] {
    const ids: string[] = [];
    for (const destination of this.subscriptions.keys()) {
      const id = auctionIdFromDestination(destination);
      if (id) ids.push(id);
    }
    return ids;
  }

  /**
   * Refetches every subscribed auction's current state via REST and fans it out
   * as a synthetic `RESYNC` message on that auction's topic destination, exactly
   * like an inbound frame (routed through the same coalescing queue). A terminal
   * `status` on the response is carried through unchanged — `useReconciledAuction`
   * is what actually applies §7.4's "terminal status wins regardless of AUCTION_ENDED" rule.
   */
  async resyncAll(): Promise<void> {
    if (this.resyncInFlight) return;
    this.resyncInFlight = true;
    try {
      const ids = this.subscribedAuctionIds();
      await Promise.all(
        ids.map(async (auctionId) => {
          try {
            const auction = await getAuction(auctionId);
            const message: ResyncMessage = {
              type: "RESYNC",
              auctionId,
              price: auction.currentPrice,
              winnerId: auction.currentWinnerId,
              endTime: auction.endTime,
              version: auction.version,
              status: auction.status,
            };
            this.enqueueFrame(auctionTopicDestination(auctionId), message);
          } catch {
            // Best-effort — a single auction's resync failing shouldn't block the
            // others or the overall reconnect; that auction's view simply stays on
            // its last-known state (still labeled not-live by the connection FSM)
            // until the next successful resync.
          }
        }),
      );
      this.drainQueueNow(); // resync results should land before the stream resumes, not wait a frame
    } finally {
      this.resyncInFlight = false;
    }
  }

  // --- Page Visibility / stale (§9) -------------------------------------------

  private attachVisibilityHandling(): void {
    if (typeof document === "undefined" || this.visibilityHandler) return;
    this.visibilityHandler = () => this.onVisibilityChange();
    document.addEventListener("visibilitychange", this.visibilityHandler);
  }

  private detachVisibilityHandling(): void {
    if (this.visibilityHandler && typeof document !== "undefined") {
      document.removeEventListener("visibilitychange", this.visibilityHandler);
    }
    this.visibilityHandler = null;
    if (this.hiddenTimer) {
      clearTimeout(this.hiddenTimer);
      this.hiddenTimer = null;
    }
  }

  private onVisibilityChange(): void {
    if (typeof document === "undefined") return;
    if (document.hidden) {
      if (this.hiddenTimer) return;
      this.hiddenTimer = setTimeout(() => {
        this.hiddenTimer = null;
        if (useConnectionStore.getState().status === "live") {
          useConnectionStore.getState().dispatch("tab_hidden_timeout");
        }
      }, HIDDEN_STALE_TIMEOUT_MS);
    } else {
      if (this.hiddenTimer) {
        clearTimeout(this.hiddenTimer);
        this.hiddenTimer = null;
      }
      if (useConnectionStore.getState().status === "stale") {
        void this.resyncOnForeground();
      }
    }
  }

  private async resyncOnForeground(): Promise<void> {
    try {
      await this.resyncAll();
      useConnectionStore.getState().dispatch("tab_visible_resynced");
    } catch {
      useConnectionStore.getState().dispatch("tab_visible_resync_failed");
      this.scheduleReconnect();
    }
  }

  // --- Inbound frame handling / tick coalescing (§11) -------------------------

  private onFrame(destination: Destination, frame: IMessage): void {
    let message: RawMessage;
    try {
      message = JSON.parse(frame.body) as RawMessage;
    } catch {
      return; // malformed frame — drop rather than crash the manager
    }
    this.enqueueFrame(destination, message);
  }

  private enqueueFrame(destination: Destination, message: RawMessage): void {
    this.frameQueue.push({ destination, message });
    this.scheduleDrain();
  }

  private scheduleDrain(): void {
    if (this.rafHandle !== null) return;
    if (typeof requestAnimationFrame === "function") {
      this.rafHandle = requestAnimationFrame(() => {
        this.rafHandle = null;
        this.drainQueueNow();
      });
    } else {
      // No rAF available (e.g. a non-DOM test environment) — drain on the next
      // microtask so tests don't need to fake an animation frame.
      this.rafHandle = -1;
      queueMicrotask(() => {
        this.rafHandle = null;
        this.drainQueueNow();
      });
    }
  }

  /**
   * Drains the buffered queue and applies every message in one batch (§11): all
   * clock-offset samples are recorded and all registered callbacks are invoked
   * synchronously here, so React 18+'s automatic batching coalesces any resulting
   * re-renders into a single paint regardless of how many messages arrived.
   */
  private drainQueueNow(): void {
    if (this.rafHandle !== null && typeof cancelAnimationFrame !== "undefined" && this.rafHandle >= 0) {
      cancelAnimationFrame(this.rafHandle);
    }
    this.rafHandle = null;
    const frames = this.frameQueue.splice(0, this.frameQueue.length);
    const receivedAt = Date.now();
    for (const { destination, message } of frames) {
      if ("serverNow" in message && typeof message.serverNow === "string") {
        useClockOffsetStore.getState().recordServerNow(message.serverNow, receivedAt);
      }
      const callbacks = this.subscriptions.get(destination);
      if (!callbacks) continue;
      for (const callback of callbacks) callback(message);
    }
  }
}

let instance: ConnectionManager | null = null;

/**
 * The only accessor for the singleton (§7.1 point 0). Throws if called outside a
 * browser context — this must only ever be invoked from a `useEffect` or other
 * client-only code path, never at module-eval time, so a Server Component that
 * transitively imports this module still never executes this function.
 */
export function getConnectionManager(): ConnectionManager {
  if (typeof window === "undefined") {
    throw new Error(
      "getConnectionManager() called during server render — the connection manager " +
        "is client-only (FRONTEND-PDR §7.1 point 0). Call it only from a useEffect " +
        "or other client-only code path.",
    );
  }
  if (!instance) {
    instance = new ConnectionManager();
  }
  return instance;
}

/** Test-only escape hatch to reset the module singleton between test cases. */
export function __resetConnectionManagerForTests(): void {
  instance?.disconnect();
  instance = null;
}
