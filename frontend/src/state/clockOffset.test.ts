import { describe, expect, it } from "vitest";
import { estimateOffset, recordSample, correctedNow, type OffsetSample } from "./clockOffset";

describe("recordSample (§10.1)", () => {
  it("computes a one-way offset — serverNow minus local receive time, no round-trip midpoint", () => {
    const serverNow = new Date(1_700_000_005_000).toISOString();
    const receivedAt = 1_700_000_000_000;
    const sample = recordSample(serverNow, receivedAt);
    expect(sample.offsetMs).toBe(5000);
    expect(sample.observedAt).toBe(receivedAt);
  });

  it("produces a negative offset when the server clock reads behind the local receive time", () => {
    const serverNow = new Date(999_000).toISOString();
    const sample = recordSample(serverNow, 1_000_000);
    expect(sample.offsetMs).toBe(-1000);
  });
});

describe("estimateOffset (§10.1) — median-of-5 smoothing", () => {
  it("returns null before the first sample (§10.2's 'before first sample' fallback)", () => {
    expect(estimateOffset([])).toBeNull();
  });

  it("returns the single sample's offset when only one has been recorded", () => {
    const samples: OffsetSample[] = [{ offsetMs: 42, observedAt: 0 }];
    expect(estimateOffset(samples)).toBe(42);
  });

  it("is the median, not the mean, of the most recent 5 samples", () => {
    // Offsets: 100, 102, 98, 500 (outlier), 101 -> sorted: 98,100,101,102,500 -> median 101
    const samples: OffsetSample[] = [100, 102, 98, 500, 101].map((offsetMs, i) => ({
      offsetMs,
      observedAt: i,
    }));
    expect(estimateOffset(samples)).toBe(101);
    // A mean would have been (100+102+98+500+101)/5 = 180.2 — confirm the median
    // is NOT dragged toward the single 500ms jitter outlier.
    const mean = samples.reduce((sum, s) => sum + s.offsetMs, 0) / samples.length;
    expect(estimateOffset(samples)).not.toBe(mean);
  });

  it("only considers the most recent 5 samples, ignoring older ones", () => {
    // 6 samples; the oldest (1000ms, a huge outlier) must be dropped by slice(-5).
    const samples: OffsetSample[] = [1000, 100, 101, 99, 100, 102].map((offsetMs, i) => ({
      offsetMs,
      observedAt: i,
    }));
    // Most recent 5: 100,101,99,100,102 -> sorted 99,100,100,101,102 -> median 100
    expect(estimateOffset(samples)).toBe(100);
  });

  it("a single outlier tick does not visibly jump the estimate", () => {
    const steady: OffsetSample[] = [50, 51, 49, 50, 50].map((offsetMs, i) => ({ offsetMs, observedAt: i }));
    const withOutlier: OffsetSample[] = [...steady.slice(1), { offsetMs: 5000, observedAt: 99 }];
    const before = estimateOffset(steady)!;
    const after = estimateOffset(withOutlier)!;
    expect(Math.abs(after - before)).toBeLessThan(5); // median barely moves
  });
});

describe("correctedNow (§10.1)", () => {
  it("adds the offset to the local clock", () => {
    const now = Date.now();
    expect(correctedNow(1000)).toBeGreaterThanOrEqual(now + 1000);
    expect(correctedNow(-1000)).toBeLessThanOrEqual(now);
  });
});
