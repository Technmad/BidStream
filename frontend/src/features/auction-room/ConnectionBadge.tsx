"use client";

import type { ConnectionState } from "@/src/state/connectionStore";

/**
 * §9's visible connection indicator, per state's exact table:
 * - connecting: subtle "connecting…" indicator.
 * - live: no chrome — the normal state, shouldn't announce itself.
 * - reconnecting: a visible (not alarming) "Reconnecting…" banner.
 * - stale: same visual treatment as reconnecting (§9: "same treatment as
 *   reconnecting visually, entered proactively").
 */
const LABELS: Record<ConnectionState, string> = {
  connecting: "Connecting…",
  live: "Live",
  reconnecting: "Reconnecting…",
  stale: "Reconnecting…",
};

const STYLES: Record<ConnectionState, string> = {
  connecting: "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300",
  live: "bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300",
  reconnecting: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  stale: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
};

export function ConnectionBadge({ connectionState }: { connectionState: ConnectionState }) {
  // "live" shouldn't announce itself (§9) — render a minimal dot instead of a
  // full pill so it stays present (useful for debugging) without being chrome.
  if (connectionState === "live") {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-gray-500">
        <span className="h-2 w-2 rounded-full bg-green-500" aria-hidden="true" />
        Live
      </span>
    );
  }

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${STYLES[connectionState]}`}
      role="status"
    >
      <span className="h-2 w-2 animate-pulse rounded-full bg-current" aria-hidden="true" />
      {LABELS[connectionState]}
    </span>
  );
}
