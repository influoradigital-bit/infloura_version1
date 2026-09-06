/**
 * INFLUORA ADMIN PANEL — Session / access-token lifecycle
 *
 * The admin realm is a THIRD auth realm, deliberately separate from brand/creator: it logs in
 * through `POST /api/v1/admin/auth/login`, holds its access token under `admin_token`, and its
 * refresh cookie (`influora_admin_refresh`, HttpOnly + Secure + SameSite=Strict) is path-scoped
 * to `/api/v1/admin/auth` by `AdminAuthCookieService` so it is never attached anywhere else.
 * Because of that separation it CANNOT reuse `src/lib/api.ts`'s `fetchWithAuthRetry` — that one
 * refreshes against `POST /auth/refresh`, which reads the brand/creator cookie and answers with a
 * BRAND/CREATOR token. Hence this module.
 *
 * WHY IT EXISTS: `AdminAuthController` has shipped `POST /admin/auth/refresh` (with token
 * rotation) since the P1 hardening, `api-contracts.ts` declared `authApi.refreshToken()`, and
 * login has always written the 30-day refresh cookie — but nothing on the client ever called it.
 * `apiRequest` had no 401 -> refresh -> retry, and `useAdminAuth` hard-deleted the token the
 * moment `exp` passed. The result: an admin session died unrecoverably after
 * `JWT_ACCESS_EXPIRY` (900s / 15 minutes) while brand and creator sessions silently renewed for
 * 30 days. This module is the missing caller.
 *
 * SECURITY POSTURE IS UNCHANGED: the access token stays short-lived (so a leaked one expires
 * fast, and a deactivated admin loses access within one token lifetime), and the raw refresh
 * token is still never readable by JS — it lives only in the HttpOnly cookie. Rotation is
 * enforced server-side by `AdminAuthService#refresh`, which burns the presented token on every
 * use, so a stolen refresh cookie is single-use and detectable.
 */

/**
 * Backend runs with `server.servlet.context-path: /api/v1`, and `AdminAuthController` /
 * `AdminDashboardController` mount at `/admin/**`, resolving to `/api/v1/admin/**`. This base
 * MUST carry the `/v1` segment or every admin call 404s. See `AdminAuthController` class javadoc.
 *
 * It is deliberately RELATIVE (unlike `src/lib/api.ts`'s absolute `VITE_API_BASE_URL`): the admin
 * refresh cookie is `SameSite=Strict`, so the refresh call has to be same-origin to carry it.
 * Vite proxies `/api/v1` to the Spring backend in dev (see `vite.config.ts`).
 */
export const ADMIN_API_BASE = '/api/v1/admin';

/** localStorage slot holding the admin access token. Never the refresh token — that is HttpOnly. */
export const ADMIN_TOKEN_KEY = 'admin_token';

/**
 * Refresh this many seconds BEFORE the access token's `exp`. Covers clock skew between the
 * browser and the server plus a slow request, and is short enough that we are not refreshing on
 * every call against a 900s token. Mirrors the same guard band in `src/lib/api.ts`.
 */
const REFRESH_SKEW_SECONDS = 60;

/**
 * Decodes a base64url-encoded JWT segment into its JSON payload. Returns null (never throws) on
 * any malformed input.
 *
 * This is used only to decide WHEN to ask for a new token — never to trust the token's contents.
 * The server remains the sole authority on signature verification and on what an admin may do.
 */
export function decodeJwtSegment(segment: string): Record<string, unknown> | null {
  try {
    const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const json = atob(padded);
    const parsed: unknown = JSON.parse(json);
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * Validates a JWT is well-formed (`header.payload.signature`, each segment base64url-decodable).
 * Deliberately says NOTHING about expiry — that is `isTokenExpired`'s job, and conflating the two
 * is what made an expired-but-refreshable session look identical to a garbage one.
 */
export function isTokenWellFormed(token: string): boolean {
  if (!token || typeof token !== 'string') return false;
  const parts = token.split('.');
  if (parts.length !== 3 || parts.some((part) => part.length === 0)) return false;
  return decodeJwtSegment(parts[1]) !== null;
}

/** The `exp` claim in seconds since epoch, or null if the token carries none / is unparseable. */
function tokenExpSeconds(token: string): number | null {
  if (!isTokenWellFormed(token)) return null;
  const exp = decodeJwtSegment(token.split('.')[1])?.exp;
  return typeof exp === 'number' ? exp : null;
}

/** Already past `exp`? A token with no `exp` claim is treated as not expired (the server decides). */
export function isTokenExpired(token: string): boolean {
  const exp = tokenExpSeconds(token);
  if (exp === null) return false;
  return exp <= Date.now() / 1000;
}

/** Expired, or close enough to expiry that we should renew before spending it on a real call. */
function isTokenNearExpiry(token: string): boolean {
  const exp = tokenExpSeconds(token);
  if (exp === null) return false;
  return exp - REFRESH_SKEW_SECONDS <= Date.now() / 1000;
}

export function getAdminToken(): string | null {
  return localStorage.getItem(ADMIN_TOKEN_KEY);
}

export function setAdminToken(token: string): void {
  localStorage.setItem(ADMIN_TOKEN_KEY, token);
}

export function clearAdminToken(): void {
  localStorage.removeItem(ADMIN_TOKEN_KEY);
}

/**
 * Dedupes concurrent refreshes into ONE in-flight `POST /admin/auth/refresh`.
 *
 * This matters more here than in the brand/creator client: the admin dashboard fans out a dozen
 * hooks (`usePulseData`, `useOperationsSummary`, `useTicketList`, ...) on a single page load, so
 * a cold load with a stale token would otherwise fire a dozen simultaneous refreshes. Because the
 * server ROTATES on every refresh — `AdminAuthService#refresh` revokes the presented token before
 * minting the next — the first would succeed and the rest would present an already-burned token
 * and 401, logging the admin straight back out. One promise, shared by every caller.
 */
let inFlightRefresh: Promise<string | null> | null = null;

/**
 * Exchanges the HttpOnly refresh cookie for a fresh access token and stores it.
 *
 * Returns the new token, or null when the session is genuinely over (no cookie, cookie expired,
 * token already burned, admin deactivated). Fails CLOSED and quietly: callers treat null as
 * "not signed in" and the route guards take it from there. Deliberately a raw `fetch` rather
 * than `apiRequest` — routing it through `apiRequest` would let a 401 from the refresh call
 * trigger another refresh.
 */
export function refreshAdminSession(): Promise<string | null> {
  if (inFlightRefresh) return inFlightRefresh;

  const promise = (async (): Promise<string | null> => {
    try {
      const response = await fetch(`${ADMIN_API_BASE}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // The cookie is same-origin and SameSite=Strict; this is what carries it. The empty body
        // is intentional: `AdminAuthController#refresh` prefers the cookie and only falls back to
        // a body-supplied token, and the raw refresh token is @JsonIgnore'd out of every response
        // so this client has never had a copy to send.
        credentials: 'same-origin',
        body: JSON.stringify({}),
      });

      if (!response.ok) return null;

      const body: unknown = await response.json().catch(() => null);
      const token =
        typeof body === 'object' && body !== null
          ? (body as { token?: unknown }).token
          : undefined;

      if (typeof token !== 'string' || !token) return null;

      setAdminToken(token);
      return token;
    } catch {
      // Network failure, not an auth failure. Still null — the caller falls through to the
      // request, gets its own error, and nothing has been destroyed.
      return null;
    } finally {
      inFlightRefresh = null;
    }
  })();

  inFlightRefresh = promise;
  return promise;
}

/**
 * The token to spend on the next admin API call, renewing first when the current one is at or
 * near expiry. Returns null when there is no session to renew.
 *
 * This is the PROACTIVE half of the session fix: an admin who leaves a tab idle past the access
 * token's lifetime and then clicks something gets one silent refresh instead of a visible 401.
 * The reactive half (401 -> refresh -> retry) lives in `apiRequest` and covers the cases this
 * cannot see — a token revoked server-side, or one that expires mid-flight.
 */
export async function getFreshAdminToken(): Promise<string | null> {
  const token = getAdminToken();
  if (!token) return null;

  // Not a JWT at all (corrupted slot) — there is nothing to renew against a token we cannot read,
  // but the refresh cookie may still be good, so try it rather than dead-ending the session.
  if (!isTokenWellFormed(token) || isTokenNearExpiry(token)) {
    return refreshAdminSession();
  }

  return token;
}
