import { fetchApi } from "./client";

/**
 * Verified against com.bidstream.adapter.in.rest.dto.AuthDtos (§4.1):
 * register/login both key off `username`, not email — the account's login
 * identity is the username, `email` is a separate registration field.
 */
export type RegisterRequest = {
  username: string;
  email: string;
  password: string;
};

export type LoginRequest = {
  username: string;
  password: string;
};

export type RefreshRequest = {
  refreshToken: string;
};

/** `POST /auth/login` and `POST /auth/refresh` response shape (§4.1). Access TTL 15 min, refresh TTL 7 days. */
export type AuthTokens = {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
};

/**
 * `POST /auth/register` — 201, empty body, no tokens returned (§4.1). Caller must
 * follow up with `login()` using the same username/password.
 */
export async function register(request: RegisterRequest): Promise<void> {
  await fetchApi<void>("/auth/register", { method: "POST", body: request });
}

export async function login(request: LoginRequest): Promise<AuthTokens> {
  return fetchApi<AuthTokens>("/auth/login", { method: "POST", body: request });
}

export async function refresh(request: RefreshRequest): Promise<AuthTokens> {
  return fetchApi<AuthTokens>("/auth/refresh", { method: "POST", body: request });
}
