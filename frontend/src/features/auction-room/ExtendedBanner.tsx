"use client";

import { useEffect, useState } from "react";

/**
 * §12.1's "one deliberate, distinct visual moment" for `AUCTION_EXTENDED` — the
 * anti-snipe mechanic is otherwise invisible, so this is the single UI moment
 * that actually explains why the countdown didn't hit zero when it seemed to say
 * it would. `aria-live="assertive"` per §15 (a low-frequency message the user
 * needs to notice immediately). Auto-dismisses itself after ~4s so it doesn't
 * linger as permanent chrome.
 */
export function ExtendedBanner({ newEndTime }: { newEndTime: string }) {
  // Initialized true on mount; the caller remounts this component (via a `key`
  // keyed on `newEndTime`) for each new extension, so a fresh 4s dismiss window
  // starts per extension without this effect needing to set state synchronously
  // on mount itself (react-hooks/set-state-in-effect).
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    const timer = setTimeout(() => setVisible(false), 4000);
    return () => clearTimeout(timer);
  }, []);

  if (!visible) return null;

  const formatted = new Date(newEndTime).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });

  return (
    <div
      role="alert"
      aria-live="assertive"
      className="animate-[pulse_1.2s_ease-in-out_2] rounded-lg border-2 border-amber-500 bg-amber-100 px-4 py-3 text-sm font-semibold text-amber-900 shadow-md dark:bg-amber-900/60 dark:text-amber-100"
    >
      ⏱ Auction extended — new end time {formatted}
    </div>
  );
}
