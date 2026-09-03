"use client";

import { useEffect, useState } from "react";
import { getCorrectedNow } from "@/src/state/clockOffset";

function formatRemaining(ms: number): string {
  if (ms <= 0) return "0:00";
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const mm = String(minutes).padStart(2, "0");
  const ss = String(seconds).padStart(2, "0");
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${minutes}:${ss}`;
}

/**
 * §10.2 — re-renders roughly every 100ms while not ended, computing remaining
 * time from `endTime` against the server-corrected clock (`getCorrectedNow()`,
 * falling back to raw `Date.now()` when the offset hasn't been established yet
 * per §10.2's "before first sample" case — `getCorrectedNow()` already does that
 * fallback internally).
 *
 * Per §15: NOT wrapped in an `aria-live` region, and does not re-announce every
 * second — a visual ticking number only. A screen-reader user gets the same
 * "time remaining" affordance a sighted user gets from glancing at the number,
 * not a running narration.
 */
export function Countdown({ endTime, isEnded }: { endTime: string; isEnded: boolean }) {
  const [remainingMs, setRemainingMs] = useState(() => new Date(endTime).getTime() - getCorrectedNow());

  useEffect(() => {
    if (isEnded) return;
    const endMs = new Date(endTime).getTime();
    const tick = () => setRemainingMs(endMs - getCorrectedNow());
    tick();
    const interval = setInterval(tick, 100);
    return () => clearInterval(interval);
  }, [endTime, isEnded]);

  if (isEnded) {
    return <span className="text-2xl font-semibold tabular-nums text-gray-500">Ended</span>;
  }

  return (
    <span className="text-2xl font-semibold tabular-nums" data-testid="countdown">
      {formatRemaining(remainingMs)}
    </span>
  );
}
