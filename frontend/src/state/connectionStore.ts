"use client";

import { create } from "zustand";

/**
 * Connection lifecycle state machine (§9) — the exact states and transitions from
 * the PDR's mermaid diagram:
 *
 * ```
 * [*] --> connecting
 * connecting --> live: CONNECTED frame received
 * connecting --> reconnecting: connect failed
 * live --> reconnecting: socket closed / error
 * reconnecting --> connecting: backoff elapsed, retry
 * live --> stale: tab hidden > 60s (Page Visibility API)
 * stale --> live: tab visible again + resync (§7.4) succeeds
 * ```
 *
 * The transition table (`nextConnectionState`) is a pure function, independent of
 * Zustand/React/the actual WebSocket, so it's directly unit-testable (§14.1). The
 * store wraps it with the one piece of real state (`status`) plus a couple of
 * fields the UI needs to render §9's table (last confirmed-live time, an optional
 * reason for why we're not live).
 */

export type ConnectionState = "connecting" | "live" | "reconnecting" | "stale";

export type ConnectionEvent =
  | "connect_success" // connecting -> live
  | "connect_failed" // connecting -> reconnecting
  | "socket_closed" // live -> reconnecting
  | "backoff_elapsed" // reconnecting -> connecting
  | "tab_hidden_timeout" // live -> stale (>60s hidden)
  | "tab_visible_resynced" // stale -> live
  | "tab_visible_resync_failed"; // stale -> reconnecting (resync itself failed — treat like a drop)

/**
 * Pure FSM transition function (§9). Returns the next state for a given
 * (current state, event) pair, or `current` unchanged if the event doesn't apply
 * to that state (an illegal/no-op transition is treated as a no-op rather than a
 * thrown error, since a real WebSocket can deliver events in orders a strict
 * mermaid diagram doesn't anticipate — e.g. a `socket_closed` while already
 * `reconnecting`).
 */
export function nextConnectionState(
  current: ConnectionState,
  event: ConnectionEvent,
): ConnectionState {
  switch (current) {
    case "connecting":
      if (event === "connect_success") return "live";
      if (event === "connect_failed") return "reconnecting";
      return current;
    case "live":
      if (event === "socket_closed") return "reconnecting";
      if (event === "tab_hidden_timeout") return "stale";
      return current;
    case "reconnecting":
      if (event === "backoff_elapsed") return "connecting";
      return current;
    case "stale":
      if (event === "tab_visible_resynced") return "live";
      if (event === "tab_visible_resync_failed") return "reconnecting";
      return current;
    default:
      return current;
  }
}

type ConnectionStoreState = {
  status: ConnectionState;
  /** Epoch ms of the last time we transitioned into `live`, or `null` before the first connect. */
  lastLiveAt: number | null;
  dispatch: (event: ConnectionEvent) => void;
  /** Test/debug helper — resets to the FSM's initial state. */
  reset: () => void;
};

export const useConnectionStore = create<ConnectionStoreState>((set, get) => ({
  status: "connecting",
  lastLiveAt: null,
  dispatch: (event) => {
    const next = nextConnectionState(get().status, event);
    if (next === get().status) return;
    set({
      status: next,
      lastLiveAt: next === "live" ? Date.now() : get().lastLiveAt,
    });
  },
  reset: () => set({ status: "connecting", lastLiveAt: null }),
}));
