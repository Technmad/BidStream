"use client";

import Link from "next/link";
import { useState } from "react";
import { placeBid } from "@/src/api/auctions";
import { ApiError } from "@/src/api/client";
import { submitPendingBid } from "@/src/state/pendingBids";
import type { PendingBid } from "@/src/state/pendingBids";
import type { ParsedBidResult } from "@/src/ws/contracts";
import type { PendingBidDisplayStatus } from "@/src/reconciliation/useReconciledAuction";

export type BidFormProps = {
  auctionId: string;
  /** Current confirmed price + the increment, both decimal strings — used ONLY to compute a display suggestion, never sent as-is without the user's own input. */
  confirmedPrice: string;
  minIncrement: string;
  myPendingBid: PendingBid | null;
  pendingBidStatus: PendingBidDisplayStatus;
  lastBidResult: { correlationId: string; result: ParsedBidResult } | null;
  isEnded: boolean;
  isAuthenticated: boolean;
  accessToken: string | null;
};

/** Display-only suggestion (§ task brief: "simple string-safe math... never for the authoritative amount sent"). */
function suggestNextBid(confirmedPrice: string, minIncrement: string): string {
  const suggested = Number(confirmedPrice) + Number(minIncrement);
  if (!Number.isFinite(suggested)) return "";
  return suggested.toFixed(2);
}

/**
 * §13's error matrix, applied to a *synchronous* rejection of the `placeBid` call
 * itself (distinct from the async `BID_RESULT` WS rejection path handled via
 * `lastBidResult` below — §8.3 step 4 / the task brief's explicit "don't
 * conflate them" instruction).
 */
function describeSyncBidError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) {
      // §13: "this should be rare... treat it as a bug-signal (log it) as well as showing a message."
      console.warn("BidForm: 403 placing a bid — the UI shouldn't have offered this action.", err.problem);
      return "You're not allowed to bid on this auction.";
    }
    if (err.status === 429) {
      // §4.3/§13: no retryAfterMs is ever sent — generic message, no fabricated countdown.
      return "Please slow down and try again.";
    }
    if (err.status === 400 && err.problem.errors) {
      const messages = Object.values(err.problem.errors);
      if (messages.length > 0) return messages.join(" ");
    }
    if (err.status === 409) {
      const reason = typeof err.problem.reason === "string" ? err.problem.reason : null;
      const currentPrice = err.problem.currentPrice;
      const minIncrement = err.problem.minIncrement;
      if (currentPrice !== undefined && minIncrement !== undefined) {
        return `Bid rejected (${reason ?? "invalid amount"}) — current price is ${currentPrice}, minimum increment is ${minIncrement}.`;
      }
      return reason ? `Bid rejected: ${reason}` : "Bid rejected.";
    }
    return err.problem.detail ?? err.problem.title ?? "Could not place bid.";
  }
  return "Could not place bid. Please try again.";
}

/**
 * §12.1/§14's full bid state machine: idle → submitting → pending →
 * accepted/rejected/timed-out. `pendingBidStatus`/`lastBidResult` are read from
 * `useReconciledAuction`'s output (the caller, `AuctionRoom`, owns that hook —
 * this component is driven purely by props, which is also what makes it testable
 * per §14 by mocking the reconciliation hook's output directly).
 */
export function BidForm({
  auctionId,
  confirmedPrice,
  minIncrement,
  myPendingBid,
  pendingBidStatus,
  lastBidResult,
  isEnded,
  isAuthenticated,
  accessToken,
}: BidFormProps) {
  const [amount, setAmount] = useState(() => suggestNextBid(confirmedPrice, minIncrement));
  const [submitting, setSubmitting] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  const isBusy = submitting || pendingBidStatus === "pending";
  const disabled = isEnded || !isAuthenticated || isBusy;

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!isAuthenticated || !accessToken || isEnded) return;
    setSyncError(null);
    setSubmitting(true);
    try {
      const response = await placeBid(auctionId, amount, accessToken);
      submitPendingBid({
        correlationId: response.correlationId,
        auctionId,
        amount,
        submittedAt: Date.now(),
      });
    } catch (err) {
      setSyncError(describeSyncBidError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (isEnded) {
    return <p className="text-sm text-gray-500">This auction has ended — bidding is closed.</p>;
  }

  if (!isAuthenticated) {
    return (
      <p className="text-sm text-gray-600 dark:text-gray-300">
        <Link href="/login" className="underline">
          Log in
        </Link>{" "}
        to bid.
      </p>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-3" noValidate>
      <label className="flex flex-col gap-1 text-sm">
        Your bid (USD)
        <input
          type="number"
          inputMode="decimal"
          step="0.01"
          min="0"
          name="amount"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          disabled={disabled}
          required
          className="rounded border border-gray-300 px-3 py-2 disabled:opacity-50 dark:border-gray-700 dark:bg-transparent"
        />
      </label>

      <button
        type="submit"
        disabled={disabled}
        className="rounded bg-foreground px-4 py-2 text-sm font-medium text-background disabled:opacity-50"
      >
        {submitting ? "Submitting…" : pendingBidStatus === "pending" ? "Confirming…" : "Place bid"}
      </button>

      {pendingBidStatus === "pending" && myPendingBid && (
        <p role="status" className="text-sm text-gray-600 dark:text-gray-300">
          Your bid of {myPendingBid.amount} is confirming…
        </p>
      )}

      {pendingBidStatus === "still-processing" && myPendingBid && (
        <p role="status" className="text-sm text-gray-600 dark:text-gray-300">
          Still processing your bid of {myPendingBid.amount} — you&apos;ll be notified. This is not a failure; no
          need to retry.
        </p>
      )}

      {lastBidResult && lastBidResult.result.outcome === "REJECTED" && (
        <p role="alert" aria-live="assertive" className="text-sm text-red-600 dark:text-red-400">
          Your bid was rejected: {lastBidResult.result.reason}
        </p>
      )}

      {lastBidResult && lastBidResult.result.outcome === "ACCEPTED" && (
        <p role="status" aria-live="assertive" className="text-sm text-green-700 dark:text-green-400">
          Your bid was accepted!
        </p>
      )}

      {syncError && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          {syncError}
        </p>
      )}
    </form>
  );
}
