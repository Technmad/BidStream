"use client";

import { useCallback, useSyncExternalStore } from "react";
import * as authApi from "@/src/api/auth";
import { clearTokens, getAccessToken, setTokens, subscribe } from "./tokenStore";

/**
 * Thin React-facing wrapper around `tokenStore` (§12.5). Re-renders subscribers
 * whenever the in-memory access token changes (login, logout, silent refresh).
 *
 * Client-only by construction — `useSyncExternalStore` needs a browser subscription,
 * and `tokenStore` itself only holds real state in the browser (§7.1's boundary
 * applies here too: no Server Component may import this hook).
 */
export function useAuth() {
  const accessToken = useSyncExternalStore(
    subscribe,
    getAccessToken,
    () => null, // server snapshot: never authenticated during SSR (§12.5)
  );

  const login = useCallback(async (username: string, password: string) => {
    const tokens = await authApi.login({ username, password });
    setTokens(tokens);
  }, []);

  const register = useCallback(async (username: string, email: string, password: string) => {
    await authApi.register({ username, email, password });
    // §4.1: register returns no tokens — log in immediately after with the same credentials.
    const tokens = await authApi.login({ username, password });
    setTokens(tokens);
  }, []);

  const logout = useCallback(() => {
    clearTokens();
  }, []);

  return {
    accessToken,
    isAuthenticated: accessToken !== null,
    login,
    register,
    logout,
  };
}
