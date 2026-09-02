/**
 * Base URL of the BidStream STOMP/WebSocket endpoint (FRONTEND-PDR.md §4.2, §7).
 * Defaults to the backend's local dev address; override via NEXT_PUBLIC_WS_BASE_URL
 * for other environments. Follows the same NEXT_PUBLIC_* convention as
 * `src/api/client.ts`'s `API_BASE_URL` — public because the connection is opened
 * from the browser (§7.1).
 *
 * `@stomp/stompjs`'s `Client` accepts a `brokerURL` using `ws://`/`wss://` (it opens
 * a plain `WebSocket` itself — no SockJS needed, per the backend's plain STOMP
 * endpoint with `setAllowedOriginPatterns("*")`). We accept an `http(s)://`-style
 * default (matching the shape of `API_BASE_URL`) and normalize it to `ws(s)://`
 * in `connectionManager.ts` so the env var stays consistent with its REST sibling
 * regardless of which scheme a caller happens to set it with.
 */
export const WS_BASE_URL =
  process.env.NEXT_PUBLIC_WS_BASE_URL ?? "http://localhost:8080/ws";

/** Normalizes an http(s)/ws(s) URL to the ws(s) scheme `@stomp/stompjs`'s `brokerURL` expects. */
export function toBrokerUrl(url: string): string {
  if (url.startsWith("https://")) return "wss://" + url.slice("https://".length);
  if (url.startsWith("http://")) return "ws://" + url.slice("http://".length);
  return url; // already ws:// or wss://
}
