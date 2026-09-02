"use client";

import { useEffect } from "react";
import { getConnectionManager } from "./connectionManager";

/**
 * The client-only provider that mounts the connection manager (§7.1 point 0,
 * §17's comment in `app/layout.tsx`/`app/providers.tsx`: "the client-only
 * connection-manager provider (§7)"). Renders nothing — its only job is to call
 * `getConnectionManager()` inside a `useEffect`, i.e. strictly after mount, so the
 * singleton is never constructed and the STOMP connection is never opened during
 * server render.
 *
 * Mounted once, inside `Providers` (`app/providers.tsx`), alongside the
 * `QueryClientProvider` — see that file's comment for why this is the documented
 * slot rather than a new provider boundary.
 */
export function ConnectionManagerProvider({ children }: { children: React.ReactNode }) {
  useEffect(() => {
    const manager = getConnectionManager();
    manager.connect();
    // No `manager.disconnect()` on unmount: this provider lives for the app's
    // whole lifetime (mounted once in the root layout) — a real unmount only
    // happens on full page teardown, which the browser handles itself. Tearing
    // the singleton down here would also fight React 18 Strict Mode's
    // mount→unmount→mount dev-only double-invoke, since `connect()`/`disconnect()`
    // aren't cheap to flap; `connect()` is already idempotent (a no-op if already
    // connected/connecting), which is what actually makes Strict Mode safe here.
  }, []);

  return <>{children}</>;
}
