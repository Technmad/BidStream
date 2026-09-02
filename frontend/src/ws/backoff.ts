/**
 * Exponential backoff with jitter (§7.3) — a pure function so the exact schedule
 * (1s/2s/4s/8s/16s, capped at 30s, ±20% jitter) is unit-testable without a real
 * WebSocket or timers.
 *
 * `attempt` is 0-indexed (the first retry after a drop is attempt 0). No maximum
 * attempt count is enforced here — §7.3 is explicit that the manager keeps
 * retrying forever; `attempt` only affects the delay, capped at 30s regardless of
 * how high it climbs.
 */
export function computeBackoffDelay(
  attempt: number,
  random: () => number = Math.random,
): number {
  const baseMs = Math.min(1000 * 2 ** Math.max(attempt, 0), 30_000);
  const jitterFactor = 1 + (random() * 0.4 - 0.2); // ±20%
  return Math.round(baseMs * jitterFactor);
}
