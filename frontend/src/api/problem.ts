/**
 * RFC 7807 (`application/problem+json`) error shape returned by the backend's
 * GlobalExceptionHandler for every non-2xx response — see FRONTEND-PDR.md §4.1, §13.
 *
 * Two shapes to code against by name (§4.1):
 *  - A validation 400 carries `errors: {field: message}`.
 *  - A bid-rejection 409 carries `reason`, `currentPrice`, `minIncrement` as extra
 *    properties (not modeled here since bid endpoints are Phase 1+, but the `[key: string]`
 *    index signature already accommodates them without a type change later).
 */
export type Problem = {
  type?: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Present on a validation 400 (§4.1). */
  errors?: Record<string, string>;
  /** Present on some errors, e.g. a bid 409's `reason`/`currentPrice`/`minIncrement`, or a 429's `reason: "RATE_LIMITED"`. */
  [key: string]: unknown;
};

/**
 * Thrown by `fetchApi` for any non-2xx response. Carries the parsed RFC 7807 body
 * (or a best-effort fallback if the body wasn't valid JSON) plus the HTTP status,
 * so callers can branch on `error.status` / `error.problem` per the error matrix (§13).
 */
export class ApiError extends Error {
  readonly problem: Problem;
  readonly status: number;

  constructor(problem: Problem, status: number) {
    super(problem.title || problem.detail || `Request failed with status ${status}`);
    this.name = "ApiError";
    this.problem = problem;
    this.status = status;
  }
}
