/**
 * A small, deterministic, purely cosmetic display function for a bidder id
 * (FRONTEND-PDR.md §12.1's "Bidder-identity display" decision).
 *
 * Applied everywhere a bidder identity would otherwise render — the Live Auction
 * Room's high-bidder display, the leaderboard, the bid history — so the choice is
 * internally consistent across the whole screen rather than split per-widget
 * (the exact inconsistency the PDR's v1.1 correction caught and fixed).
 *
 * This is a readability choice, NOT a privacy feature, and must never be
 * labeled or documented as one: the backend does not pseudonymize bidder
 * identity anywhere (`winnerId`/`currentWinnerId`/`bidderId` are the same raw
 * UUID everywhere the API returns one), and the raw id is always visible in the
 * network tab regardless of what this function renders. A UUID is simply
 * unpleasant to read next to a price; a short deterministic handle is not.
 */
export function displayHandle(id: string): string {
  return `Bidder ${id.slice(0, 6)}`;
}
