import { describe, expect, it } from "vitest";
import { computeBackoffDelay } from "./backoff";

const noJitter = () => 0.5; // random()=0.5 -> jitterFactor = 1 (midpoint of ±20%)

describe("computeBackoffDelay (§7.3)", () => {
  it("follows 1s/2s/4s/8s/16s for attempts 0-4, with no jitter at the midpoint", () => {
    expect(computeBackoffDelay(0, noJitter)).toBe(1000);
    expect(computeBackoffDelay(1, noJitter)).toBe(2000);
    expect(computeBackoffDelay(2, noJitter)).toBe(4000);
    expect(computeBackoffDelay(3, noJitter)).toBe(8000);
    expect(computeBackoffDelay(4, noJitter)).toBe(16000);
  });

  it("caps at 30s regardless of how high the attempt count climbs", () => {
    expect(computeBackoffDelay(5, noJitter)).toBe(30000);
    expect(computeBackoffDelay(10, noJitter)).toBe(30000);
    expect(computeBackoffDelay(100, noJitter)).toBe(30000);
  });

  it("applies +/-20% jitter around the base delay", () => {
    const base = 1000;
    const min = base * 0.8;
    const max = base * 1.2;
    for (const random of [0, 0.25, 0.5, 0.75, 1]) {
      const delay = computeBackoffDelay(0, () => random);
      expect(delay).toBeGreaterThanOrEqual(min);
      expect(delay).toBeLessThanOrEqual(max);
    }
  });

  it("jitter never pushes the capped 30s delay above 36s (30s * 1.2)", () => {
    const delay = computeBackoffDelay(20, () => 1);
    expect(delay).toBeLessThanOrEqual(30000 * 1.2);
  });
});
