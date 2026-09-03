"use client";

import { useState } from "react";
import { cancelAutoBid, setAutoBid } from "@/src/api/auctions";
import { ApiError } from "@/src/api/client";

function storageKey(auctionId: string): string {
  return `bidstream.lastAutoBidMax.${auctionId}`;
}

/** Guarded localStorage access, mirroring `tokenStore.ts`'s SSR/private-browsing pattern. */
function readLastAutoBidMax(auctionId: string): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(storageKey(auctionId));
  } catch {
    return null;
  }
}

function writeLastAutoBidMax(auctionId: string, maxAmount: string): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(storageKey(auctionId), maxAmount);
  } catch {
    // ignore — worst case the display hint doesn't persist
  }
}

function clearLastAutoBidMax(auctionId: string): void {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(storageKey(auctionId));
  } catch {
    // ignore
  }
}

export type AutoBidFormProps = {
  auctionId: string;
  isEnded: boolean;
  isAuthenticated: boolean;
  accessToken: string | null;
};

/**
 * §12.1's auto-bid workaround (decision b): there is no `GET` to read back a
 * user's own standing auto-bid max (§4.3), so the last-submitted max is
 * persisted to `localStorage` purely as a *display hint*, labeled "you last set
 * $X (this device)" — never "your current max is $X", since local storage can
 * drift from server truth (a different device, a cleared cache).
 */
export function AutoBidForm({ auctionId, isEnded, isAuthenticated, accessToken }: AutoBidFormProps) {
  const [maxAmount, setMaxAmount] = useState("");
  const [lastSet, setLastSet] = useState<string | null>(() => readLastAutoBidMax(auctionId));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  if (!isAuthenticated) return null;

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!accessToken || isEnded) return;
    setError(null);
    setMessage(null);
    setSubmitting(true);
    try {
      await setAutoBid(auctionId, maxAmount, accessToken);
      writeLastAutoBidMax(auctionId, maxAmount);
      setLastSet(maxAmount);
      setMessage("Auto-bid max set.");
    } catch (err) {
      setError(err instanceof ApiError ? err.problem.detail ?? err.problem.title : "Could not set auto-bid.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCancel() {
    if (!accessToken) return;
    setError(null);
    setMessage(null);
    setSubmitting(true);
    try {
      await cancelAutoBid(auctionId, accessToken);
      clearLastAutoBidMax(auctionId);
      setLastSet(null);
      setMessage("Auto-bid cancelled.");
    } catch (err) {
      setError(err instanceof ApiError ? err.problem.detail ?? err.problem.title : "Could not cancel auto-bid.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex flex-col gap-2 rounded border border-gray-300 p-3 dark:border-gray-700">
      <h3 className="text-sm font-semibold">Auto-bid</h3>
      {lastSet && (
        <p className="text-xs text-gray-500">You last set ${lastSet} (this device)</p>
      )}
      <form onSubmit={handleSubmit} className="flex items-end gap-2" noValidate>
        <label className="flex flex-1 flex-col gap-1 text-sm">
          Max amount (USD)
          <input
            type="number"
            inputMode="decimal"
            step="0.01"
            min="0"
            value={maxAmount}
            onChange={(e) => setMaxAmount(e.target.value)}
            disabled={isEnded || submitting}
            required
            className="rounded border border-gray-300 px-3 py-2 disabled:opacity-50 dark:border-gray-700 dark:bg-transparent"
          />
        </label>
        <button
          type="submit"
          disabled={isEnded || submitting}
          className="rounded bg-foreground px-3 py-2 text-sm font-medium text-background disabled:opacity-50"
        >
          Set
        </button>
        {lastSet && (
          <button
            type="button"
            onClick={handleCancel}
            disabled={submitting}
            className="rounded border border-gray-300 px-3 py-2 text-sm font-medium disabled:opacity-50 dark:border-gray-700"
          >
            Cancel
          </button>
        )}
      </form>
      {message && (
        <p role="status" className="text-xs text-green-700 dark:text-green-400">
          {message}
        </p>
      )}
      {error && (
        <p role="alert" className="text-xs text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
