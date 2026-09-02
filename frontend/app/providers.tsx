"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import { useState } from "react";
import { ConnectionManagerProvider } from "@/src/ws/ConnectionManagerProvider";

/**
 * The root client-only provider boundary (FRONTEND-PDR.md §7.1, §17).
 *
 * Every provider that must run client-side lives here — today just TanStack
 * Query. Phase 1's connection-manager provider (§7) slots in alongside it, in
 * this same component, without restructuring `app/layout.tsx` again.
 *
 * The `"use client"` directive is what makes this safe: this component (and
 * everything constructed inside it, like `new QueryClient()`) never executes
 * during server render, so nothing here can trip §7.1's SSR-safety rule
 * ("no Server Component may import from `ws/`/`state/` even indirectly").
 * `app/layout.tsx` — a Server Component — only imports this module's default
 * export as a component to mount, never anything that runs at import time.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  // Constructed once per browser session via useState's lazy initializer, not
  // at module scope — a module-scope QueryClient would be shared across
  // concurrent requests if this ever ran server-side, which it doesn't, but
  // the pattern is worth keeping correct on principle (it's also required if
  // this ever needs to run in a test harness that mounts multiple times).
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Live auction data is never read through Query (§5.1) — this
            // staleTime only governs plain REST reads (categories, auction
            // lists/detail), where a short cache window avoids refetching on
            // every mount without going stale in a way a user would notice.
            staleTime: 30_000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      {/* Phase 1's connection-manager provider (§7) — slots in here, alongside
          TanStack Query's provider, exactly as this file's own comment above
          anticipated; no restructuring of app/layout.tsx needed. */}
      <ConnectionManagerProvider>{children}</ConnectionManagerProvider>
      {process.env.NODE_ENV === "development" && <ReactQueryDevtools initialIsOpen={false} />}
    </QueryClientProvider>
  );
}
