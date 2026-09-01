import { ApiError, type Problem } from "./problem";

/**
 * Base URL of the BidStream REST API (§4.1). Defaults to the backend's local dev
 * address; override via NEXT_PUBLIC_API_BASE_URL for other environments. Public
 * (NEXT_PUBLIC_*) because this must be readable from the browser — every
 * authenticated call happens client-side (§12.5) and the SSR-proof page (§5.2)
 * needs the same value on the server.
 */
export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1";

export type FetchApiOptions = Omit<RequestInit, "body"> & {
  /** Plain object/array/primitive to JSON-encode as the request body. */
  body?: unknown;
  /**
   * Bearer token to attach, if any. Deliberately a parameter rather than something
   * this module reads from shared state — this file must stay callable from Server
   * Components (no access token exists there, §12.5) and from client hooks alike (§5.2, §17).
   */
  accessToken?: string | null;
};

/**
 * Framework-agnostic typed fetch wrapper against the BidStream REST API.
 *
 * Callable from both Server Components (direct `fetch`, no token) and client-side
 * hooks (token supplied by the auth module) per §5.2/§17 — it does not import
 * anything from `ws/`, `state/`, or any client-only module, and never touches
 * `window`/`localStorage` itself, so it is safe to import transitively from a
 * Server Component without violating §7.1's SSR-safety rule.
 *
 * On any non-2xx response, throws `ApiError` with the parsed RFC 7807 body (§4.1, §13).
 */
export async function fetchApi<T>(path: string, options: FetchApiOptions = {}): Promise<T> {
  const { body, accessToken, headers, ...rest } = options;

  const finalHeaders = new Headers(headers);
  finalHeaders.set("Accept", "application/json");
  if (body !== undefined) {
    finalHeaders.set("Content-Type", "application/json");
  }
  if (accessToken) {
    finalHeaders.set("Authorization", `Bearer ${accessToken}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...rest,
    headers: finalHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    let problem: Problem;
    try {
      problem = (await response.json()) as Problem;
    } catch {
      problem = { title: response.statusText || "Request failed", status: response.status };
    }
    throw new ApiError(problem, response.status);
  }

  // §4.1: register is 201 with an empty body; watch/unwatch are 204. Nothing to parse.
  if (response.status === 204 || response.status === 205) {
    return undefined as T;
  }

  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

export { ApiError };
export type { Problem };
