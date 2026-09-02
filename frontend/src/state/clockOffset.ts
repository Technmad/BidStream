"use client";

import { create } from "zustand";

/**
 * Server-authoritative clock offset (§10). The pure functions here are copied
 * exactly from the PDR's §10.1 code sample — the v1.1-corrected, one-way estimate,
 * NOT a round-trip midpoint (there is no client-sent request behind a broadcast
 * push, so there is no round trip to measure). A small Zustand store wraps them so
 * the connection manager can record samples and any UI (Phase 2's countdown) can
 * read the smoothed offset reactively.
 */

export type OffsetSample = { offsetMs: number; observedAt: number };

/**
 * Called every time any message carrying `serverNow` arrives (every `PRICE_UPDATE`
 * tick, `AUCTION_EXTENDED`, `AUCTION_ENDED`) — NOT only once at connect. This is a
 * ONE-WAY estimate: there is no client-sent request behind a broadcast push, so
 * there is no round trip to measure and no midpoint to compute — offset is simply
 * `serverNow` minus local receive time.
 */
export function recordSample(serverNow: string, receivedAt: number): OffsetSample {
  const offsetMs = new Date(serverNow).getTime() - receivedAt;
  return { offsetMs, observedAt: receivedAt };
}

/**
 * Smoothed over the last N samples (N=5) via median, not mean — a single
 * network-jitter outlier (one slow tick) should not visibly jump the countdown;
 * median is robust to exactly that, where a mean would drag the whole estimate
 * toward one bad sample. This does NOT protect against a sustained, asymmetric
 * latency trend — an accepted gap per §10.1, not a guarantee overclaimed as airtight.
 *
 * Returns `null` if no samples have been recorded yet (§10.2's "before first sample"
 * case) — callers render against the raw, uncorrected clock in that case.
 */
export function estimateOffset(samples: OffsetSample[]): number | null {
  if (samples.length === 0) return null;
  const recent = samples.slice(-5).map((s) => s.offsetMs).sort((a, b) => a - b);
  return recent[Math.floor(recent.length / 2)];
}

/** What every countdown actually renders against (§10.1). */
export function correctedNow(offsetMs: number): number {
  return Date.now() + offsetMs;
}

const MAX_SAMPLES = 5;

type ClockOffsetState = {
  samples: OffsetSample[];
  /** Smoothed offset in ms, or `null` before the first sample (§10.2). */
  offsetMs: number | null;
  /** Records a new sample from a message's `serverNow` field and recomputes the smoothed offset. */
  recordServerNow: (serverNow: string, receivedAt?: number) => void;
  /** Test/debug helper — clears all recorded samples. */
  reset: () => void;
};

export const useClockOffsetStore = create<ClockOffsetState>((set, get) => ({
  samples: [],
  offsetMs: null,
  recordServerNow: (serverNow, receivedAt = Date.now()) => {
    const sample = recordSample(serverNow, receivedAt);
    const samples = [...get().samples, sample].slice(-MAX_SAMPLES);
    set({ samples, offsetMs: estimateOffset(samples) });
  },
  reset: () => set({ samples: [], offsetMs: null }),
}));

/** Non-hook accessor for use outside React (the connection manager itself, §7.1 point 5). */
export function getCorrectedNow(): number {
  const offsetMs = useClockOffsetStore.getState().offsetMs;
  return offsetMs === null ? Date.now() : correctedNow(offsetMs);
}
