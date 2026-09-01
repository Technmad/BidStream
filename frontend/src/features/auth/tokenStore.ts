"use client";

/**
 * Token storage per FRONTEND-PDR.md §12.5.
 *
 * - Access token: **in memory only** (a module-level variable), never localStorage —
 *   reduces XSS exposure of a live bearer token. This means it is, by construction,
 *   invisible to server render: no Server Component can ever read it (§5.2, §12.5).
 *   It resets on a hard page reload, which is exactly why `getValidAccessToken()`
 *   transparently re-derives it from the refresh token below when it's missing.
 * - Refresh token: the backend returns it as a plain JSON field with no cookie
 *   mechanism (§12.5's stated gap — an `httpOnly` cookie would be better but that's
 *   backend-facing work, §20). `localStorage` is the pragmatic fallback used here so
 *   a page reload doesn't force a re-login, matching FE-1.
 *
 * This module is deliberately framework-agnostic React-wise (no hooks) so it can be
 * called from `src/api` request helpers as easily as from a component; `useAuth`
 * (in this same directory) is the thin React-facing subscription on top of it.
 */

const REFRESH_TOKEN_STORAGE_KEY = "bidstream.refreshToken";

let accessToken: string | null = null;
const listeners = new Set<() => void>();

function emitChange(): void {
  for (const listener of listeners) listener();
}

export function getAccessToken(): string | null {
  return accessToken;
}

function setAccessTokenInternal(token: string | null): void {
  accessToken = token;
  emitChange();
}

export function getRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
  } catch {
    // Private browsing / storage disabled — treat as "no refresh token."
    return null;
  }
}

function setRefreshTokenInternal(token: string | null): void {
  if (typeof window === "undefined") return;
  try {
    if (token) {
      window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, token);
    } else {
      window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    }
  } catch {
    // ignore — worst case the user has to log in again after a reload
  }
}

/** Store a fresh access/refresh pair, e.g. after login/register or a refresh call. */
export function setTokens(tokens: { accessToken: string; refreshToken: string }): void {
  setRefreshTokenInternal(tokens.refreshToken);
  setAccessTokenInternal(tokens.accessToken);
}

/** Clear both tokens — logout, or a refresh attempt that itself failed (refresh token expired/invalid). */
export function clearTokens(): void {
  setAccessTokenInternal(null);
  setRefreshTokenInternal(null);
}

/** True if we're holding a live access token OR could silently get one via the refresh token. */
export function isAuthenticated(): boolean {
  return accessToken !== null || getRefreshToken() !== null;
}

/** Subscribe to access-token changes (for `useSyncExternalStore`-based hooks). */
export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

let refreshInFlight: Promise<string | null> | null = null;

/**
 * Returns a usable access token, transparently refreshing via the refresh token
 * when there isn't one in memory (e.g. right after a page reload) — the primitive
 * §7.2 (WS connect) and §13 (REST 401 handling) both build on. Concurrent callers
 * share one in-flight refresh rather than racing multiple `/auth/refresh` calls.
 *
 * Returns null if there is no refresh token, or refreshing fails (expired/invalid
 * refresh token) — callers should treat null as "not logged in, redirect to login."
 */
export async function getValidAccessToken(): Promise<string | null> {
  if (accessToken) return accessToken;
  return refreshAccessToken();
}

/** Forces a refresh regardless of whether an access token is currently held — used by 401 retry logic (§13). */
export async function refreshAccessToken(): Promise<string | null> {
  if (refreshInFlight) return refreshInFlight;

  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;

  refreshInFlight = (async () => {
    try {
      // Lazy import avoids a circular dependency: src/api/auth.ts doesn't depend on
      // this module, but keeping the import local here makes that direction explicit.
      const { refresh } = await import("@/src/api/auth");
      const tokens = await refresh({ refreshToken });
      setTokens(tokens);
      return tokens.accessToken;
    } catch {
      clearTokens();
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}
