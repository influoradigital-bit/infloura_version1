/**
 * Influora API Client
 * ----------------------------------------------------------------------------
 * Single source of truth for backend endpoints. Every UI action that needs to
 * talk to the server goes through `api.<resource>.<method>(...)`.
 *
 * Backend contract (REST, JSON over HTTPS):
 *   - All endpoints are prefixed with `/api/v1`
 *   - Auth via `Authorization: Bearer <jwt>` (brand_token or creator_token)
 *   - Wrapped response envelope:
 *       { success: boolean, data?: T, error?: { code, message }, meta?: {...} }
 *   - Pagination: ?page=1&limit=20 — meta returns { page, limit, total, hasMore }
 *   - Idempotency: mutating calls accept `Idempotency-Key` header
 *   - Webhooks (server → client via SSE on /api/v1/stream):
 *       proposal.received, message.new, contract.signed, payment.released,
 *       deliverable.submitted, wallet.low_balance
 *
 * Mock mode is enabled when VITE_API_MODE !== 'live'. Switch by setting
 *   VITE_API_MODE=live in .env.local
 */

import type {
  Campaign,
  CampaignStatus,
  CollaborationStatus,
  ContractStatus,
  CreatorProfile,
  CreatorDemographics,
  CreatorMetrics,
  CreatorScores,
  CreatorScoresSummary,
  DeliverableStatus,
  Platform,
  PlatformStats,
  TargetAudience,
  VerificationStatus,
} from './types';
import {
  persistBrandSession,
  persistCreatorSession,
  type BackendTokenPair,
  type BrandSession,
} from './auth-session';

// Re-export types needed by consumers
export type { DeliverableStatus, ContractStatus } from './types';

// ---------------------------------------------------------------------------
// Environment / config
// ---------------------------------------------------------------------------

const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

const API_MODE: 'live' | 'mock' =
  import.meta.env?.VITE_API_MODE === 'live' ? 'live' : 'mock';

/** True when `VITE_API_MODE=live` */
export function isApiLive(): boolean {
  return API_MODE === 'live';
}

/**
 * CR-11 — resolves the `__APP_BUILD_ID__` Vite `define` (vite.config.ts) to a value that is
 * safe to read from ANY test/build context, not just a real `vite build`.
 *
 * `typeof __APP_BUILD_ID__` rather than a bare reference: when the define replaces the
 * identifier (a real Vite build, or `npm run dev`), this evaluates the substituted literal
 * as intended. When nothing replaces it (vitest's configs — vitest.config.ts /
 * vitest.live.config.ts — deliberately aren't touched for this ticket and carry no such
 * `define`), the identifier is never declared as a runtime binding — `declare const` in
 * vite-env.d.ts is type-only — so referencing it directly would throw `ReferenceError` the
 * first time any test imports this module. `typeof` on an unresolvable identifier is the
 * one JS construct that returns `'undefined'` instead of throwing, so this line is safe in
 * both worlds without needing either vitest config to know about this build id at all.
 */
const APP_BUILD_ID: string = typeof __APP_BUILD_ID__ !== 'undefined' ? __APP_BUILD_ID__ : 'dev';

/**
 * Fail-closed mock guard (Kabir A3). Mock mode (hardcoded `mock_brand_token` /
 * `mock_creator_token`, no credential check) is only ever acceptable in a
 * non-production build. If a production build somehow ships with
 * `VITE_API_MODE` unset/misconfigured (`isApiLive() === false` in `import.meta.env.PROD`),
 * that is a broken deploy where "anyone is logged in" — auth would be a no-op.
 * Rather than silently minting a mock token in that state, fail closed: throw
 * so the caller renders a config-error screen instead of a fake session.
 */
export class MockAuthDisabledError extends Error {
  constructor() {
    super('Mock authentication is disabled in production builds. VITE_API_MODE must be "live".');
    this.name = 'MockAuthDisabledError';
  }
}

export function assertMockAuthAllowed(): void {
  if (import.meta.env.PROD && !isApiLive()) {
    throw new MockAuthDisabledError();
  }
}

const TOKEN_KEYS = {
  brand: 'brand_token',
  creator: 'creator_token',
} as const;

/**
 * CR-121 — "Remember me" was a checkbox with no `checked`/`onChange` at all; checking it did
 * nothing. Backed here rather than faked: the flag records where the ACCESS token lives —
 * `localStorage` (survives closing the browser) when remembered, `sessionStorage` (cleared on
 * tab/window close) when not. The refresh token is out of scope for this control by design —
 * it is never in JS-readable storage at all (Kabir A1), delivered only as an HttpOnly cookie —
 * so unchecking this narrows exposure of the access token, not the full session length.
 */
const REMEMBER_ME_KEYS = {
  brand: 'brand_remember_me',
  creator: 'creator_remember_me',
} as const;

export type Role = 'brand' | 'creator';

// ---------------------------------------------------------------------------
// Response envelope + error class
// ---------------------------------------------------------------------------

/**
 * Mirrors the backend's `ApiErrorBody` record (influora-api/.../common/ApiErrorBody.java)
 * exactly, field-for-field. `requiredAmount`/`walletBalance`/`shortfallAmount`/`currency` are
 * additive and `NON_NULL` on the wire (class-level `@JsonInclude(Include.NON_NULL)` drops them
 * entirely when absent) — populated ONLY on the `INSUFFICIENT_FUNDS` 402 from
 * `POST /wallet/escrow/fund` (see `InsufficientFundsException`); every other error response
 * omits them. Jackson serializes a Java record's components by their declared parameter names
 * (no `@JsonProperty` override on `ApiErrorBody`), so these camelCase keys are exact.
 */
export interface ApiErrorPayload {
  code: string;
  message: string;
  field?: string;
  fields?: Array<{ field: string; message: string }>;
  requiredAmount?: number;
  walletBalance?: number;
  shortfallAmount?: number;
  currency?: string;
}

export interface ApiEnvelope<T> {
  success: boolean;
  data?: T;
  error?: ApiErrorPayload;
  meta?: { page?: number; limit?: number; total?: number; hasMore?: boolean };
}

/**
 * The server-computed `INSUFFICIENT_FUNDS` 402 figures, carried on `ApiError.details`. All four
 * fields come from the exact same balance read that gates the escrow charge (never re-derived or
 * estimated client-side) — see `InsufficientFundsException`'s Javadoc. Optional because
 * `ApiError` is the generic error type for every endpoint; only the `INSUFFICIENT_FUNDS` 402
 * populates this.
 */
export interface InsufficientFundsDetails {
  requiredAmount: number;
  walletBalance: number;
  shortfallAmount: number;
  currency: string;
}

/**
 * Extracts the `INSUFFICIENT_FUNDS` 402 shortfall figures from a parsed error payload, if
 * present. Returns `undefined` for every other error (or an older/edge server that 402s without
 * the additive fields) so callers can distinguish "no server shortfall available" from "server
 * says shortfall is 0" and fall back gracefully instead of re-estimating client-side.
 */
export function extractInsufficientFundsDetails(
  error: ApiErrorPayload | undefined,
): InsufficientFundsDetails | undefined {
  if (!error || error.shortfallAmount == null || error.requiredAmount == null || error.walletBalance == null || !error.currency) {
    return undefined;
  }
  return {
    requiredAmount: error.requiredAmount,
    walletBalance: error.walletBalance,
    shortfallAmount: error.shortfallAmount,
    currency: error.currency,
  };
}

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number,
    /** Populated only for `INSUFFICIENT_FUNDS` 402s that carry the server-computed shortfall. */
    public details?: InsufficientFundsDetails,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ---------------------------------------------------------------------------
// Low-level HTTP client
// ---------------------------------------------------------------------------

/**
 * How long before a token's `exp` we proactively renew it. Wide enough to absorb client/server
 * clock skew and a slow request, narrow enough that we are not refreshing on every call.
 */
const TOKEN_REFRESH_SKEW_MS = 60_000;

/**
 * Reads the `exp` claim (seconds since epoch) out of a JWT without verifying it.
 *
 * Verification is emphatically the server's job — this is used only to decide *when* to ask for
 * a new token, never to decide whether the current one is trusted. A forged `exp` can at worst
 * make the client refresh earlier or later than ideal; the server still rejects a bad token.
 *
 * Returns null for anything that is not a JWT carrying a numeric `exp` — notably the mock-mode
 * tokens (`mock_brand_token`), which must not trigger refresh attempts.
 */
function decodeJwtExpSeconds(token: string): number | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    // base64url → base64, then pad to a multiple of 4 for atob.
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    const claims = JSON.parse(atob(padded)) as { exp?: unknown };
    return typeof claims.exp === 'number' ? claims.exp : null;
  } catch {
    return null;
  }
}

class HttpClient {
  /** Dedupes concurrent 401s for the same role into a single `/auth/refresh` call (H-19). */
  private refreshPromises: Partial<Record<Role, Promise<string | null>>> = {};

  /** CR-121 — which storage currently holds (or should hold) this role's token, per its
   *  remembered preference. Absent flag defaults to `localStorage` — every pre-existing call
   *  site that never passed `remember` keeps its exact prior behavior. */
  private tokenStorage(role: Role): Storage {
    return localStorage.getItem(REMEMBER_ME_KEYS[role]) === 'false' ? sessionStorage : localStorage;
  }

  private getToken(role: Role = 'brand'): string | null {
    // Reads whichever storage actually holds it — covers the moment right after `clearToken`
    // flipped a role's preference but a caller still passes a stale role/session combination.
    return localStorage.getItem(TOKEN_KEYS[role]) ?? sessionStorage.getItem(TOKEN_KEYS[role]);
  }

  /**
   * `remember` is optional and, when omitted, does NOT change the stored preference — this
   * matters because the token-refresh path (`fetchWithAuthRetry`) calls `setToken(role, newToken)`
   * with no third argument on every silent renewal, and that call must keep writing to whichever
   * storage the original login chose, not silently upgrade a session-only login to persistent.
   */
  setToken(role: Role, token: string, remember?: boolean): void {
    if (remember !== undefined) {
      localStorage.setItem(REMEMBER_ME_KEYS[role], String(remember));
    }
    this.tokenStorage(role).setItem(TOKEN_KEYS[role], token);
  }

  clearToken(role: Role): void {
    localStorage.removeItem(TOKEN_KEYS[role]);
    sessionStorage.removeItem(TOKEN_KEYS[role]);
    localStorage.removeItem(REMEMBER_ME_KEYS[role]);
  }

  /**
   * Bootstraps a session by hitting `POST /auth/refresh` (reads the HttpOnly refresh
   * cookie server-side) and stashing the rotated access token. Reuses the same refresh
   * flow as the reactive 401 interceptor below. Never throws — fails closed to "no token".
   */
  async bootstrap(role: Role): Promise<boolean> {
    const token = await this.refreshAccessToken(role);
    return !!token;
  }

  private headers(role: Role, extra?: Record<string, string>): HeadersInit {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...extra,
    };
    const token = this.getToken(role);
    if (token) h.Authorization = `Bearer ${token}`;
    return h;
  }

  /**
   * H-19: 401 → refresh → retry interceptor. On a 401 from an authenticated request,
   * calls `POST /auth/refresh` (Kabir A1 — reads the HttpOnly refresh cookie server-side,
   * this SPA never sees the refresh token) exactly once, swaps in the rotated access
   * token, and retries the original request a single time. Concurrent 401s for the same
   * role share one in-flight refresh call instead of each firing their own.
   */
  private async refreshAccessToken(role: Role): Promise<string | null> {
    const inFlight = this.refreshPromises[role];
    if (inFlight) return inFlight;

    const promise = (async () => {
      try {
        const res = await fetch(`${API_BASE_URL}/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          credentials: 'include',
        });
        if (!res.ok) return null;
        const envelope = (await res.json()) as ApiEnvelope<{ accessToken: string; expiresIn: number }>;
        if (!envelope.success || !envelope.data?.accessToken) return null;
        this.setToken(role, envelope.data.accessToken);
        return envelope.data.accessToken;
      } catch {
        return null;
      } finally {
        delete this.refreshPromises[role];
      }
    })();

    this.refreshPromises[role] = promise;
    return promise;
  }

  /**
   * Refreshes the access token BEFORE a request when it is about to expire.
   *
   * The reactive 401 path below is a safety net, not a strategy: it only helps once the server
   * has already rejected a request, and it is defeated entirely if the server answers expiry
   * with anything other than 401 — which is exactly what happened until 2026-07-26, when Spring
   * returned a bodyless 403 for unauthenticated requests and no refresh ever fired.
   *
   * Refreshing ahead of expiry removes that dependency: the user's session renews silently for
   * as long as the 30-day refresh cookie lives, regardless of how the server phrases a rejection.
   *
   * Returns the new token if one was fetched, else null (caller keeps the existing header).
   * Deliberately quiet on failure — `refreshAccessToken` already fails closed, and a failed
   * proactive refresh simply falls through to the request, the 401, and the reactive path.
   */
  private async ensureFreshToken(role: Role): Promise<string | null> {
    const token = this.getToken(role);
    if (!token) return null;
    const exp = decodeJwtExpSeconds(token);
    // Not a JWT (mock-mode tokens like `mock_brand_token`) or no `exp` claim — nothing to
    // anticipate, so leave it alone and let the reactive path handle any rejection.
    if (exp === null) return null;
    if (exp * 1000 - Date.now() > TOKEN_REFRESH_SKEW_MS) return null;
    return this.refreshAccessToken(role);
  }

  /**
   * Runs `fetch`; on a 401 (and only once per call) attempts a refresh + retry with the
   * new token. If refresh fails, clears the stale token and returns the original 401
   * response so the caller's normal envelope-error handling takes over.
   *
   * Note the retry is 401-only by design. A 403 means "authenticated but not permitted" — the
   * OWNER/ADMIN role gates on campaign actions, for instance — and refreshing changes nothing,
   * so retrying those would loop on requests the server is correctly refusing.
   */
  private async fetchWithAuthRetry(
    url: string,
    init: RequestInit,
    role: Role,
    hasAuthHeader: boolean,
    retried = false,
  ): Promise<Response> {
    if (hasAuthHeader && !retried) {
      const fresh = await this.ensureFreshToken(role);
      if (fresh) {
        init = {
          ...init,
          headers: { ...(init.headers as Record<string, string>), Authorization: `Bearer ${fresh}` },
        };
      }
    }
    const res = await fetch(url, init);
    if (res.status === 401 && hasAuthHeader && !retried) {
      const newToken = await this.refreshAccessToken(role);
      if (newToken) {
        const headers = { ...(init.headers as Record<string, string>), Authorization: `Bearer ${newToken}` };
        return this.fetchWithAuthRetry(url, { ...init, headers }, role, hasAuthHeader, true);
      }
      this.clearToken(role);
    }
    return res;
  }

  async request<T>(
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    opts: {
      role?: Role;
      body?: unknown;
      query?: Record<string, string | number | boolean | undefined>;
      idempotencyKey?: string;
    } = {},
  ): Promise<T> {
    const { role = 'brand', body, query, idempotencyKey } = opts;
    const url = new URL(`${API_BASE_URL}${path}`);
    if (query) {
      Object.entries(query).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') {
          url.searchParams.set(k, String(v));
        }
      });
    }

    const headers = this.headers(role, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined);

    const res = await this.fetchWithAuthRetry(
      url.toString(),
      {
        method,
        headers,
        // Kabir A1 — send/receive the HttpOnly refresh cookie. The refresh token is never in JS;
        // it rides only in this cookie (CORS allowCredentials is enabled server-side).
        credentials: 'include',
        body: body ? JSON.stringify(body) : undefined,
      },
      role,
      !!this.getToken(role),
    );

    const envelope = await this.parseEnvelope<T>(res);

    if (!res.ok || !envelope.success) {
      throw new ApiError(
        envelope.error?.code || 'UNKNOWN',
        envelope.error?.message || res.statusText,
        res.status,
        extractInsufficientFundsDetails(envelope.error),
      );
    }

    return envelope.data as T;
  }

  /**
   * Parse the response body as the API envelope, or throw a clear, honest error when the
   * body is not JSON. A non-JSON body almost never means "corrupt data" — it means the
   * request never reached the API: a proxy/gateway HTML error page while the backend is
   * restarting, a 502/503/504, or the network dropped. Surfacing that as a misleading
   * "Invalid JSON" made a transient outage look like a data bug; instead we tell the user
   * the server was briefly unavailable so the UI's "Try again" reads correctly.
   */
  private async parseEnvelope<T>(res: Response): Promise<ApiEnvelope<T>> {
    const raw = await res.text();
    try {
      return JSON.parse(raw) as ApiEnvelope<T>;
    } catch {
      const unavailable = res.status === 0 || res.status === 502 || res.status === 503 || res.status === 504;
      throw new ApiError(
        unavailable ? 'SERVER_UNAVAILABLE' : 'BAD_RESPONSE',
        unavailable
          ? `The server was briefly unavailable (${res.status || 'no response'}). Please try again in a moment.`
          : `Unexpected non-JSON response from the server (HTTP ${res.status}).`,
        res.status,
      );
    }
  }

  /**
   * Same as {@link request} but preserves `envelope.meta` (pagination) instead of
   * discarding it — used by list endpoints whose UI needs `hasMore`/`total`.
   */
  async requestWithMeta<T>(
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    opts: {
      role?: Role;
      body?: unknown;
      query?: Record<string, string | number | boolean | undefined>;
      idempotencyKey?: string;
    } = {},
  ): Promise<{ data: T; meta: ApiEnvelope<T>['meta'] }> {
    const { role = 'brand', body, query, idempotencyKey } = opts;
    const url = new URL(`${API_BASE_URL}${path}`);
    if (query) {
      Object.entries(query).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') {
          url.searchParams.set(k, String(v));
        }
      });
    }
    const headers = this.headers(role, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined);
    const res = await this.fetchWithAuthRetry(
      url.toString(),
      {
        method,
        headers,
        credentials: 'include',
        body: body ? JSON.stringify(body) : undefined,
      },
      role,
      !!this.getToken(role),
    );
    const envelope = await this.parseEnvelope<T>(res);
    if (!res.ok || !envelope.success) {
      throw new ApiError(
        envelope.error?.code || 'UNKNOWN',
        envelope.error?.message || res.statusText,
        res.status,
        extractInsufficientFundsDetails(envelope.error),
      );
    }
    return { data: envelope.data as T, meta: envelope.meta };
  }

  /**
   * Same as {@link request} but treats HTTP 204 (No Content) as a valid empty result —
   * returns `null` instead of throwing on the empty body. Needed for endpoints that use
   * 204 as a deliberate "nothing to say" signal rather than an error (e.g. the Trend-Spark
   * nudge endpoint, T4/T7 — Snapsby-TrendSpark-AI-Spec.md §5b anti-spam gate: silence is
   * the correct, expected response when the score is below threshold).
   */
  async requestOrNull<T>(
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    opts: {
      role?: Role;
      body?: unknown;
      query?: Record<string, string | number | boolean | undefined>;
      idempotencyKey?: string;
    } = {},
  ): Promise<T | null> {
    const { role = 'brand', body, query, idempotencyKey } = opts;
    const url = new URL(`${API_BASE_URL}${path}`);
    if (query) {
      Object.entries(query).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') {
          url.searchParams.set(k, String(v));
        }
      });
    }
    const headers = this.headers(role, idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined);
    const res = await this.fetchWithAuthRetry(
      url.toString(),
      {
        method,
        headers,
        credentials: 'include',
        body: body ? JSON.stringify(body) : undefined,
      },
      role,
      !!this.getToken(role),
    );
    if (res.status === 204) return null;
    const envelope = await this.parseEnvelope<T>(res);
    if (!res.ok || !envelope.success) {
      throw new ApiError(
        envelope.error?.code || 'UNKNOWN',
        envelope.error?.message || res.statusText,
        res.status,
        extractInsufficientFundsDetails(envelope.error),
      );
    }
    return (envelope.data as T) ?? null;
  }

  async upload<T>(path: string, file: File, role: Role = 'brand'): Promise<T> {
    const formData = new FormData();
    formData.append('file', file);
    return this.uploadForm<T>(path, formData, role);
  }

  /**
   * Multipart POST with a caller-built {@link FormData} — used when the endpoint expects
   * a non-`file` part name, multiple files, or extra form fields (e.g. the deliverable
   * upload route whose part name is `files` (list) plus optional `thumbnail`/`caption`).
   * Do NOT set a `Content-Type` header — the browser sets the multipart boundary itself.
   */
  async uploadForm<T>(path: string, formData: FormData, role: Role = 'brand'): Promise<T> {
    const token = this.getToken(role);
    const res = await this.fetchWithAuthRetry(
      `${API_BASE_URL}${path}`,
      {
        method: 'POST',
        body: formData,
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      },
      role,
      !!token,
    );
    const envelope = (await res.json()) as ApiEnvelope<T>;
    if (!res.ok || !envelope.success) {
      throw new ApiError(envelope.error?.code || 'UPLOAD_FAILED', envelope.error?.message || 'Upload failed');
    }
    return envelope.data as T;
  }

  /**
   * Raw binary GET (e.g. invoice PDFs) — these endpoints return the file body directly,
   * not the `ApiEnvelope` JSON wrapper, so they need the Bearer token attached manually
   * rather than going through `request<T>`'s JSON parsing.
   */
  async downloadBlob(path: string, role: Role = 'brand'): Promise<Blob> {
    const token = this.getToken(role);
    const res = await this.fetchWithAuthRetry(
      `${API_BASE_URL}${path}`,
      {
        method: 'GET',
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      },
      role,
      !!token,
    );
    if (!res.ok) {
      throw new ApiError('DOWNLOAD_FAILED', `Failed to download ${path}`, res.status);
    }
    return res.blob();
  }
}

const http = new HttpClient();

// ---------------------------------------------------------------------------
// Mock helpers — used until VITE_API_MODE=live
// ---------------------------------------------------------------------------

const delay = (ms = 400) => new Promise((r) => setTimeout(r, ms));

async function mockOr<T>(value: T | Promise<T>): Promise<T> {
  await delay();
  return value;
}

const isLive = () => API_MODE === 'live';

// ---------------------------------------------------------------------------
// Auth + onboarding
// ---------------------------------------------------------------------------

export interface LoginPayload { email: string; password: string }
export interface LoginResponse {
  token: string;
  userId: string;
  onboardingComplete: boolean;
  /**
   * CR-06 — the authenticated identity, carried through from the backend's
   * `TokenPair.user` so the caller can populate the auth store instead of
   * leaving `user` null and letting the shell fall back to a demo profile.
   * Optional because mock mode has no real user behind it.
   */
  email?: string;
  displayName?: string;
}

export interface BrandRegisterPayload {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  companyName: string;
  industry?: string;
  companySize?: string;
  acceptedTerms: boolean;
  phone?: string;
}

/**
 * Mirrors `CreatorRegisterRequest` (influora-api/.../dto/auth/CreatorRegisterRequest.java):
 * either `displayName` or both `firstName`+`lastName` must be present — enforced
 * server-side by `@AssertTrue isNameValid()`.
 */
export interface CreatorRegisterPayload {
  email: string;
  password: string;
  firstName?: string;
  lastName?: string;
  displayName?: string;
  acceptedTerms: boolean;
}

async function mapBrandAuth(data: BackendTokenPair): Promise<LoginResponse> {
  const session: BrandSession = persistBrandSession(data);
  return {
    token: session.token,
    userId: session.userId,
    onboardingComplete: session.onboardingComplete,
  };
}

export interface CompanyDetailsPayload {
  companyName: string;
  companySlug: string;
  workspaceType: 'BRAND' | 'AGENCY';
  industry: string;
  companySize: string;
  websiteUrl?: string;
  description?: string;
  logoUrl?: string;
}

export const auth = {
  /** POST /auth/brand/login */
  brandLogin: async (payload: LoginPayload) => {
    if (!isLive()) {
      assertMockAuthAllowed();
      return mockOr({ token: 'mock_brand_token', userId: 'u_1', onboardingComplete: true });
    }
    const data = await http.request<BackendTokenPair>('POST', '/auth/brand/login', { body: payload });
    return mapBrandAuth(data);
  },

  /**
   * POST /auth/creator/login — real endpoint, verified against
   * AuthController.creatorLogin / AuthDtos.TokenPair. Maps the backend's
   * `BackendTokenPair` (`accessToken`/`user.id`/`onboardingCompleted`) onto
   * `LoginResponse` (`token`/`userId`/`onboardingComplete`) explicitly, the
   * same way `creatorRegister` does — `http.request<T>()` only casts
   * `envelope.data as T`, so without this mapping `result.token` would be
   * `undefined` in live mode and `setToken('creator', undefined)` would break
   * login.
   *
   * CR-06 gave the creator flow its own `persistCreatorSession`, mirroring
   * `persistBrandSession`, and it is called below — the identity fields are kept
   * here rather than thrown away, which is what left the shell with no creator to
   * display. The caller still stores the access token itself via
   * `api.auth.setToken('creator', result.token)`; this helper deliberately does not
   * touch the token.
   *
   * (CR-33 — this paragraph previously read "Creator has no `persistCreatorSession`
   * helper... the caller stores the raw token", three lines above the call to it.)
   */
  creatorLogin: async (payload: LoginPayload): Promise<LoginResponse> => {
    if (!isLive()) {
      assertMockAuthAllowed();
      return mockOr({ token: 'mock_creator_token', userId: 'cr_1', onboardingComplete: true });
    }
    const data = await http.request<BackendTokenPair>('POST', '/auth/creator/login', {
      body: payload,
      role: 'creator',
    });
    // CR-06 — mirror the brand flow: keep the identity, not just the token.
    const session = persistCreatorSession(data);
    return {
      token: data.accessToken,
      userId: session.userId,
      onboardingComplete: session.onboardingComplete,
      email: session.email,
      displayName: session.displayName,
    };
  },

  /** POST /auth/brand/register */
  brandRegister: async (payload: BrandRegisterPayload) => {
    if (!isLive()) {
      assertMockAuthAllowed();
      return mockOr({ token: 'mock_brand_token', userId: 'u_new', onboardingComplete: false });
    }
    const data = await http.request<BackendTokenPair>('POST', '/auth/brand/register', { body: payload });
    return mapBrandAuth(data);
  },

  /**
   * POST /auth/creator/register — real endpoint, verified against
   * AuthController.creatorRegister / CreatorRegisterRequest. Maps the
   * backend's `BackendTokenPair` (`accessToken`/`user.id`) onto
   * `LoginResponse` (`token`/`userId`) explicitly, so `result.token` is never
   * `undefined` in live mode. Same mapping as `creatorLogin`.
   */
  creatorRegister: async (payload: CreatorRegisterPayload): Promise<LoginResponse> => {
    if (!isLive()) {
      assertMockAuthAllowed();
      return mockOr({ token: 'mock_creator_token', userId: 'cr_new', onboardingComplete: false });
    }
    const data = await http.request<BackendTokenPair>('POST', '/auth/creator/register', {
      body: payload,
      role: 'creator',
    });
    // CR-06 — same identity capture as creatorLogin.
    const session = persistCreatorSession(data);
    return {
      token: data.accessToken,
      userId: session.userId,
      onboardingComplete: session.onboardingComplete,
      email: session.email,
      displayName: session.displayName,
    };
  },

  /** POST /auth/brand/send-email-otp */
  sendBrandEmailOtp: (email: string) =>
    isLive()
      ? http.request<{ message: string; expiresIn: number; maskedEmail: string }>(
          'POST',
          '/auth/brand/send-email-otp',
          { body: { email } },
        )
      : mockOr({ message: 'OTP sent', expiresIn: 300, maskedEmail: email }),

  /** POST /auth/brand/verify-email */
  verifyBrandEmail: (email: string, otp: string) =>
    isLive()
      ? http.request<{ emailVerified: boolean; message: string }>('POST', '/auth/brand/verify-email', {
          body: { email, otp },
        })
      : mockOr({ emailVerified: true, message: 'Verified' }),

  /**
   * POST /auth/creator/send-email-otp — AuthController.java:81. The controller delegates to the
   * SAME `BrandEmailOtpService` the brand path uses (one challenge table, one rate limiter), so
   * the request/response shapes are identical to `sendBrandEmailOtp`; only the route differs.
   * Kept as a separate method rather than a `role` parameter to mirror how every other auth call
   * in this file is split brand/creator.
   */
  sendCreatorEmailOtp: (email: string) =>
    isLive()
      ? http.request<{ message: string; expiresIn: number; maskedEmail: string }>(
          'POST',
          '/auth/creator/send-email-otp',
          { body: { email }, role: 'creator' },
        )
      : mockOr({ message: 'OTP sent', expiresIn: 300, maskedEmail: email }),

  /** POST /auth/creator/verify-email — AuthController.java:87, same service as the brand path. */
  verifyCreatorEmail: (email: string, otp: string) =>
    isLive()
      ? http.request<{ emailVerified: boolean; message: string }>(
          'POST',
          '/auth/creator/verify-email',
          { body: { email, otp }, role: 'creator' },
        )
      : mockOr({ emailVerified: true, message: 'Verified' }),

  /** POST /auth/forgot-password */
  forgotPassword: async (email: string) => {
    if (!isLive()) return mockOr({ sent: true });
    await http.request<{ message: string }>('POST', '/auth/forgot-password', { body: { email } });
    return { sent: true };
  },

  /**
   * POST /auth/reset-password — BR-03. Consumes the `token` from the emailed reset link
   * (`webBaseUrl + "/reset-password?token=" + raw` — AuthService.forgotPassword) plus the
   * user's chosen `newPassword`. Field names match `ResetPasswordRequest`
   * (web/dto/auth/ResetPasswordRequest.java) exactly: `token`, `newPassword`. Unauthenticated
   * route — the token itself is the credential, so no `role`/Bearer header is sent, same as
   * `forgotPassword` above.
   */
  resetPassword: async (payload: { token: string; newPassword: string }) => {
    if (!isLive()) return mockOr({ reset: true });
    await http.request<{ message: string }>('POST', '/auth/reset-password', { body: payload });
    return { reset: true };
  },

  /**
   * POST /me/password — BR-05. In-session password change (current + new, re-auth on current).
   * Distinct from `resetPassword` (unauthenticated, token-from-email flow) — this one requires
   * the caller's current password and an active session. Backed by `AccountController:105`
   * (`AuthService#changePassword`), rate-limited by `AuthRateLimitFilter`.
   *
   * CR-87/Priya review: `role` is REQUIRED, not defaulted. `http.request` defaults to the
   * `'brand'` token when no role is given (see its own `role = 'brand'` default), so the sole
   * caller before this fix (`brand-settings.tsx`) worked only by accident — a creator caller
   * would either 401 (no brand_token present) or, worse, silently authenticate as and rotate
   * the password of whichever brand session happened to share the browser.
   */
  changePassword: async (role: Role, payload: { currentPassword: string; newPassword: string }) => {
    if (!isLive()) return mockOr({ changed: true });
    await http.request<{ message: string }>('POST', '/me/password', { role, body: payload });
    return { changed: true };
  },

  /** POST /auth/logout
   *
   * CR-91: the request MUST carry the access token so the server can resolve the
   * principal and run `refreshTokenRepository.revokeAllForUser` — otherwise the
   * refresh tokens stay live in the DB after every logout. Send first (token still
   * present), then clear locally in `finally` so the client session always ends,
   * even if the server call fails. */
  logout: (role: Role) => {
    if (isLive()) {
      return http
        .request<{ message: string }>('POST', '/auth/logout', { role })
        .finally(() => http.clearToken(role));
    }
    http.clearToken(role);
    return mockOr({ message: 'ok' });
  },

  setToken: (role: Role, token: string, remember?: boolean) => http.setToken(role, token, remember),
};

/**
 * L-9 — {@code GET /workspaces/me} response (WorkspaceMemberDtos.WorkspaceReadResponse). `email`
 * maps to `workspaces.billing_email` server-side. `phone` shipped 2026-07-18 (migration +
 * GET/PATCH, Vikram) — backed by a real column now, no longer fabricated.
 */
export interface WorkspaceMeResponse {
  id: string;
  name: string;
  slug: string;
  email: string | null;
  phone: string | null;
  industry: string | null;
  companySize: string | null;
  websiteUrl: string | null;
  logoUrl: string | null;
  verificationStatus: VerificationStatus | null;
}

/**
 * L-9 — {@code PATCH /workspaces/me} request body (WorkspaceMemberDtos.WorkspaceUpdateRequest).
 * Full-replace semantics for every included field (not a deep merge) — a field omitted here is
 * cleared server-side. `name` is the only required field. `phone` (2026-07-18): blank/omitted
 * clears it server-side; when provided, backend validates `+ ( ) - space` + 7-15 digits.
 */
export interface WorkspaceMeUpdatePayload {
  name: string;
  email?: string;
  phone?: string;
  websiteUrl?: string;
  industry?: string;
  companySize?: string;
  description?: string;
  logoUrl?: string;
}

/** One row of GET /workspace/members — MemberResponse (WorkspaceMemberDtos.java:22). */
export interface WorkspaceMemberRow {
  id: string;
  workspaceId: string;
  userId: string;
  /** MemberRole: OWNER | ADMIN | MANAGER | MEMBER | VIEWER */
  role: string;
  /** Matches the backend record component name `active` (MemberResponse — not `isActive`). */
  active: boolean;
}

/** POST /workspace/members/invite response — WorkspaceMemberDtos.InviteResponse. */
export interface WorkspaceInviteResponse {
  id: string;
  workspaceId: string;
  email: string;
  role: string;
  status: string;
  expiresAt: string;
}

export const workspaceMembers = {
  /**
   * GET /workspace/members (WorkspaceMemberController.java:74) — the only endpoint that exposes
   * roles (there is no "my role" endpoint; `/workspaces/me` carries none). Match the row whose
   * userId === localStorage 'brand_user_id' to learn the caller's own role for UX gating.
   * Server still enforces every action — this is defense-in-depth for the UI, never the control.
   */
  list: () =>
    isLive()
      ? http.request<WorkspaceMemberRow[]>('GET', '/workspace/members')
      : mockOr<WorkspaceMemberRow[]>([
          { id: 'm_1', workspaceId: 'ws_1', userId: 'u_1', role: 'OWNER', active: true },
        ]),

  /**
   * POST /workspace/members/invite (WorkspaceMemberController.java:51) — server derives
   * `workspaceId` from the authenticated principal, never from the client. `role` must be one of
   * ADMIN, MANAGER, MEMBER, VIEWER (OWNER is not assignable via invite).
   */
  invite: (email: string, role: 'ADMIN' | 'MANAGER' | 'MEMBER' | 'VIEWER') =>
    isLive()
      ? http.request<WorkspaceInviteResponse>('POST', '/workspace/members/invite', {
          body: { email, role },
        })
      : mockOr<WorkspaceInviteResponse>({
          id: 'inv_mock',
          workspaceId: 'ws_1',
          email,
          role,
          status: 'PENDING',
          expiresAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString(),
        }),
};

export const workspaces = {
  /** GET /workspaces/slug-check?slug= */
  checkSlug: (slug: string) =>
    isLive()
      ? http.request<{ slug: string; available: boolean; suggestions: string[] }>(
          'GET',
          '/workspaces/slug-check',
          { query: { slug } },
        )
      : mockOr({ slug, available: true, suggestions: [] }),

  /**
   * GET /workspaces/me — L-9, WorkspaceController.getMyWorkspace. Any active brand member may
   * read (no OWNER/ADMIN restriction on the read side).
   */
  getMe: () =>
    isLive()
      ? http.request<WorkspaceMeResponse>('GET', '/workspaces/me')
      : mockOr<WorkspaceMeResponse>({
          id: 'ws_1',
          name: 'Tech Brands Co.',
          slug: 'tech-brands-co',
          email: 'admin@techbrands.in',
          phone: '+91 98765 43210',
          industry: null,
          companySize: null,
          websiteUrl: 'www.techbrands.in',
          logoUrl: null,
          verificationStatus: 'VERIFIED',
        }),

  /**
   * PATCH /workspaces/me — L-9, WorkspaceController.updateMyWorkspace. OWNER/ADMIN only —
   * backend returns 403 for any other role.
   */
  updateMe: (payload: WorkspaceMeUpdatePayload) =>
    isLive()
      ? http.request<WorkspaceMeResponse>('PATCH', '/workspaces/me', { body: payload })
      : mockOr<WorkspaceMeResponse>({
          id: 'ws_1',
          name: payload.name,
          slug: 'tech-brands-co',
          email: payload.email ?? null,
          phone: payload.phone ?? null,
          industry: payload.industry ?? null,
          companySize: payload.companySize ?? null,
          websiteUrl: payload.websiteUrl ?? null,
          logoUrl: payload.logoUrl ?? null,
          verificationStatus: 'VERIFIED',
        }),
};

export const onboarding = {
  /** POST /onboarding/brand/company  — minimal v2 (3-step) */
  saveBrandCompany: (payload: CompanyDetailsPayload) =>
    isLive()
      ? http.request<{ workspaceId: string }>('POST', '/onboarding/brand/company', { body: payload })
      : mockOr({ workspaceId: 'ws_1' }),

  /** POST /onboarding/brand/complete */
  completeBrand: () =>
    isLive()
      ? http.request<{ ok: true }>('POST', '/onboarding/brand/complete')
      : mockOr({ ok: true as const }),

  /**
   * POST /onboarding/brand/kyc  — deferred until first campaign needs it.
   * Backend should mark the workspace as KYC_PENDING when called, then
   * KYC_VERIFIED after admin/automated approval.
   */
  submitBrandKyc: (payload: { gstin: string; pan: string; gstinDocUrl: string; panDocUrl: string }) =>
    isLive()
      ? http.request<{ kycStatus: 'PENDING' | 'VERIFIED' }>('POST', '/onboarding/brand/kyc', { body: payload })
      : mockOr({ kycStatus: 'PENDING' as const }),

  /**
   * GET /onboarding/brand/status — OB-2/OB-1 (BrandF.md §102/§105/§91), OnboardingController.status
   * (verified at influora-api/.../web/OnboardingController.java:57). Server-authoritative read of
   * onboarding completion + whether the brand already dismissed the KYC prompt (survives across
   * devices, unlike the localStorage-only flag `brand-kyc-prompt.tsx` used to rely on alone).
   */
  getBrandStatus: () =>
    isLive()
      ? http.request<{ onboardingCompleted: boolean; kycPromptDismissed: boolean }>(
          'GET',
          '/onboarding/brand/status',
        )
      : mockOr({ onboardingCompleted: true, kycPromptDismissed: false }),

  /**
   * POST /onboarding/brand/kyc-prompt-dismissed — OB-1 (BrandF.md §105/§91),
   * OnboardingController.dismissKycPrompt (verified at .../OnboardingController.java:69). No
   * request body; principal-scoped. Idempotent. Call alongside (not instead of) the existing
   * localStorage dismiss flag in `brand-kyc-prompt.tsx` — fire-and-forget is fine, the localStorage
   * write is what keeps the prompt hidden instantly in the current tab.
   */
  dismissBrandKycPrompt: () =>
    isLive()
      ? http.request<{ kycPromptDismissed: boolean }>('POST', '/onboarding/brand/kyc-prompt-dismissed')
      : mockOr({ kycPromptDismissed: true }),

  /** POST /onboarding/creator/socials */
  connectCreatorSocial: (platform: Platform, oauthCode: string) =>
    isLive()
      ? http.request<{ platform: Platform; handle: string; followers: number }>(
          'POST',
          '/onboarding/creator/socials',
          { body: { platform, oauthCode }, role: 'creator' },
        )
      : mockOr({ platform, handle: '@priya_creates', followers: 125000 }),

  /** POST /onboarding/creator/profile */
  saveCreatorProfile: (payload: {
    displayName: string;
    bio?: string;
    verticals: string[];
    languages: string[];
    city?: string;
    rateMin: number;
    rateMax: number;
  }) =>
    isLive()
      ? http.request<{ creatorId: string }>('POST', '/onboarding/creator/profile', {
          body: payload,
          role: 'creator',
        })
      : mockOr({ creatorId: 'cr_new' }),

  /** POST /onboarding/creator/complete */
  completeCreator: () =>
    isLive()
      ? http.request<{ ok: true }>('POST', '/onboarding/creator/complete', { role: 'creator' })
      : mockOr({ ok: true as const }),

  /**
   * POST /onboarding/creator/kyc  — deferred to first withdrawal.
   */
  submitCreatorKyc: (payload: { pan: string; aadhaarLast4: string; selfieUrl: string }) =>
    isLive()
      ? http.request<{ kycStatus: 'PENDING' | 'VERIFIED' }>('POST', '/onboarding/creator/kyc', {
          body: payload,
          role: 'creator',
        })
      : mockOr({ kycStatus: 'PENDING' as const }),

  // NOTE: creator payout-method capture is NOT here. It is handled by the wallet
  // (`GET/POST /wallet/payout-methods`, wired in creator-wallet.tsx) at first
  // withdrawal. The former `saveCreatorPayout` (`POST /onboarding/creator/payout`)
  // wrapper was removed 2026-08-04 as dead/superseded (0 callers) — see
  // PROJECT-DEEP-AUDIT-2026-08-04.md §5.
};

// ---------------------------------------------------------------------------
// Campaigns
// ---------------------------------------------------------------------------

export interface CampaignListParams {
  status?: CampaignStatus | 'ALL';
  page?: number;
  limit?: number;
  search?: string;
  /**
   * D-6 (BrandF.md §12): CampaignController accepts `sortBy`/`sortOrder` (CampaignService's
   * `buildSort` — 'createdAt' | 'updatedAt' | 'title', unrecognized values fall back to
   * 'createdAt') but the frontend never sent them; the "Sort by" control only ever re-sorted
   * the single already-fetched page client-side. `'budget'`/`'progress'` have no backend Sort
   * field yet (progress isn't even a stored column — see D-3), so those two stay client-side.
   */
  sortBy?: 'createdAt' | 'updatedAt' | 'title';
  sortOrder?: 'asc' | 'desc';
}

type CampaignApiRow = Campaign & {
  collaboratorsCount?: number;
  activeCollaborations?: number;
  completedCollaborations?: number;
  totalSpend?: number;
};

// FE CampaignType ('OPEN' | 'DIRECT' | 'HYPE' — src/lib/types.ts:15) and the backend's
// CampaignIntentType (HYPE | DIRECT | REVIEW | STANDARD) do not share a vocabulary: FE 'OPEN'
// is the backend's 'STANDARD', and the backend's 'REVIEW' has no FE surface (never created by
// the brand form; treated as an open campaign on read). These two tables are the single, explicit
// translation at the HTTP boundary so campaignType round-trips losslessly instead of leaking a
// raw 'STANDARD' into a field the FE union claims can only be OPEN/DIRECT/HYPE.
const CAMPAIGN_TYPE_TO_API = { OPEN: 'STANDARD', DIRECT: 'DIRECT', HYPE: 'HYPE' } as const;
const CAMPAIGN_TYPE_FROM_API = {
  STANDARD: 'OPEN',
  REVIEW: 'OPEN',
  DIRECT: 'DIRECT',
  HYPE: 'HYPE',
} as const;

function mapCampaignFromApi(row: CampaignApiRow): Campaign {
  const timeline = row.timeline as Campaign['timeline'];
  return {
    ...row,
    // Map the backend CampaignIntentType back to the FE CampaignType union so this field is
    // never the runtime lie it used to be ('STANDARD' arriving where the type says OPEN/DIRECT/HYPE).
    campaignType: row.campaignType
      ? CAMPAIGN_TYPE_FROM_API[row.campaignType as keyof typeof CAMPAIGN_TYPE_FROM_API] ?? 'OPEN'
      : undefined,
    timeline: {
      startDate: timeline?.startDate ? new Date(timeline.startDate) : new Date(),
      endDate: timeline?.endDate ? new Date(timeline.endDate) : new Date(),
    },
    applicationDeadline: row.applicationDeadline
      ? new Date(row.applicationDeadline as unknown as string)
      : undefined,
    createdAt: row.createdAt ? new Date(row.createdAt as unknown as string) : new Date(),
    updatedAt: row.updatedAt ? new Date(row.updatedAt as unknown as string) : new Date(),
    // Backend keeps HypeConfigDto.liveUntil as a raw ISO-8601 string (see
    // CampaignDtos.java:43-47), but HypeConfig.liveUntil is typed `Date`
    // FE-side (src/lib/types.ts:225) — convert on read same as the other
    // timestamp fields above. `hype` itself is entirely absent (NON_NULL) on
    // non-Hype campaigns, so this only fires when there's a real block to fix up.
    hype: row.hype
      ? { ...row.hype, liveUntil: new Date(row.hype.liveUntil as unknown as string) }
      : row.hype,
  };
}

function campaignToPayload(payload: Partial<Campaign>) {
  const timeline = payload.timeline as { startDate?: Date | string; endDate?: Date | string } | undefined;
  const fmt = (d?: Date | string) => {
    if (!d) return undefined;
    // A date-only string is already LocalDate-shaped — pass it through untouched.
    if (typeof d === 'string') {
      const m = /^(\d{4}-\d{2}-\d{2})/.exec(d);
      if (m) return m[1];
    }
    const date = d instanceof Date ? d : new Date(d);
    if (Number.isNaN(date.getTime())) return undefined;
    // Build yyyy-MM-dd from LOCAL calendar components, NOT toISOString() — the
    // date picker constructs Dates at local midnight, and toISOString() shifts
    // to UTC first, rolling the date back a day for any UTC+ timezone (e.g. IST
    // 00:00 → 18:30 prior UTC day). That silently stored the wrong LocalDate.
    const y = date.getFullYear();
    const mo = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${y}-${mo}-${day}`;
  };
  // Full ISO-8601 timestamp (not the date-only `fmt` above) — matches
  // HypeConfigDto.liveUntil on the backend, kept as a raw string there
  // (CampaignDtos.java:43-47 — JsonLists' plain ObjectMapper has no
  // jackson-datatype-jsr310, so a typed Instant would fail on persist).
  const fmtIso = (d: Date | string) => (d instanceof Date ? d : new Date(d)).toISOString();

  // Translate the FE CampaignType to the backend CampaignIntentType via the explicit table
  // (CAMPAIGN_TYPE_TO_API above): FE 'OPEN' -> 'STANDARD' (the backend's equivalent), DIRECT/HYPE
  // pass through. Sending 'STANDARD' explicitly is identical in outcome to the old omit-and-let-
  // the-backend-default-to-STANDARD (CampaignService.java:115-116) but is lossless and honest.
  const campaignType = payload.campaignType
    ? CAMPAIGN_TYPE_TO_API[payload.campaignType] ?? undefined
    : undefined;

  const hype = payload.hype
    ? {
        sourceReelUrl: payload.hype.sourceReelUrl,
        audioTrack: payload.hype.audioTrack,
        hashtag: payload.hype.hashtag,
        formatLanes: payload.hype.formatLanes,
        perReelRate: payload.hype.perReelRate,
        currency: payload.hype.currency,
        slotCap: payload.hype.slotCap,
        slotsFilled: payload.hype.slotsFilled,
        liveUntil: fmtIso(payload.hype.liveUntil),
      }
    : undefined;

  return {
    title: payload.title,
    description: payload.description,
    objectives: payload.objectives,
    campaignType,
    hype,
    status: payload.status,
    budget: payload.budget,
    timeline: timeline
      ? { startDate: fmt(timeline.startDate), endDate: fmt(timeline.endDate) }
      : undefined,
    applicationDeadline: fmt(payload.applicationDeadline as Date | string | undefined),
    platforms: payload.platforms,
    contentTypes: payload.contentTypes,
    requirements: payload.requirements,
    hashtags: payload.hashtags,
    brandGuidelines: payload.brandGuidelines,
    isPrivate: payload.isPrivate,
    maxCollaborators: payload.maxCollaborators,
    targetAudience: (payload as { targetAudience?: unknown }).targetAudience,
  };
}

export interface CampaignListResult {
  campaigns: Campaign[];
  meta: { page: number; limit: number; total?: number; hasMore: boolean };
}

export const campaigns = {
  /**
   * GET /campaigns?status=&page=&limit=&search= — verified against CampaignService.list, which
   * hard-caps `limit` at 100 server-side and returns pagination meta via `result.meta()` (same
   * `ApiResponse.ok(items, meta)` envelope shape as GET /creators). Uses `requestWithMeta` (not
   * `request`) so callers can paginate instead of silently truncating past the server's page-size
   * ceiling — mirrors `creators.search`'s pattern (D-2 fix; campaigns never got its own).
   */
  list: async (params: CampaignListParams = {}): Promise<CampaignListResult> => {
    if (!isLive()) {
      return mockOr<CampaignListResult>({
        campaigns: [],
        meta: { page: params.page ?? 1, limit: params.limit ?? 20, hasMore: false },
      });
    }
    const status =
      params.status && params.status !== 'ALL' ? String(params.status) : undefined;
    const { data, meta } = await http.requestWithMeta<CampaignApiRow[]>('GET', '/campaigns', {
      query: {
        page: params.page,
        limit: params.limit,
        search: params.search,
        status,
        sortBy: params.sortBy,
        sortOrder: params.sortOrder,
      },
    });
    return {
      campaigns: data.map(mapCampaignFromApi),
      meta: {
        page: meta?.page ?? params.page ?? 1,
        limit: meta?.limit ?? params.limit ?? 20,
        total: meta?.total,
        hasMore: Boolean(meta?.hasMore),
      },
    };
  },

  /** GET /campaigns/:id */
  get: async (id: string) => {
    if (!isLive()) return mockOr<Campaign | null>(null);
    const row = await http.request<CampaignApiRow>('GET', `/campaigns/${id}`);
    return mapCampaignFromApi(row);
  },

  /** POST /campaigns */
  create: async (payload: Partial<Campaign>) => {
    if (!isLive()) return mockOr(mapCampaignFromApi({ ...payload, id: 'c_new' } as Campaign));
    const row = await http.request<CampaignApiRow>('POST', '/campaigns', {
      body: campaignToPayload(payload),
    });
    return mapCampaignFromApi(row);
  },

  /** PATCH /campaigns/:id */
  update: async (id: string, payload: Partial<Campaign>) => {
    if (!isLive()) return mockOr(mapCampaignFromApi({ ...payload, id } as Campaign));
    const row = await http.request<CampaignApiRow>('PATCH', `/campaigns/${id}`, {
      body: campaignToPayload(payload),
    });
    return mapCampaignFromApi(row);
  },

  /** DELETE /campaigns/:id */
  delete: (id: string) =>
    isLive()
      ? http.request<{ ok: boolean }>('DELETE', `/campaigns/${id}`)
      : mockOr({ ok: true as const }),

  /** POST /campaigns/:id/duplicate */
  duplicate: (id: string) =>
    isLive()
      ? http.request<{ id: string }>('POST', `/campaigns/${id}/duplicate`)
      : mockOr({ id: 'c_dup' }),

  /**
   * GET /campaigns/:id/analytics — verified against CampaignController.analytics /
   * AnalyticsDtos.CampaignAnalyticsResponse. Every number here is creator-self-reported
   * (`source` is always `"CREATOR_REPORTED"`) — never platform-verified. Callers must not
   * present these as verified metrics (backend's own non-negotiable honesty rule).
   */
  analytics: (id: string) =>
    isLive()
      ? http.request<CampaignAnalytics>('GET', `/campaigns/${id}/analytics`)
      : mockOr<CampaignAnalytics | null>(null),
};

export interface CampaignAnalyticsDeliverable {
  id: string;
  milestoneId: string;
  collaborationId: string;
  reach: number | null;
  impressions: number | null;
  engagements: number | null;
  link: string | null;
  proofScreenshotR2Key: string | null;
  reportedByCreatorId: string;
  reportedAt: string;
  source: string;
}

export interface CampaignAnalytics {
  campaignId: string;
  totalReach: number;
  totalImpressions: number;
  totalEngagements: number;
  /** `engagements / impressions` as a percentage; `null` when impressions are 0/unreported. */
  derivedEngagementRate: number | null;
  deliverablesReported: number;
  deliverablesTotal: number;
  /** Always `"CREATOR_REPORTED"` — see backend AnalyticsDtos honesty rule. */
  source: string;
  deliverables: CampaignAnalyticsDeliverable[];
}

// ---------------------------------------------------------------------------
// Creators (discovery)
// ---------------------------------------------------------------------------

export interface CreatorSearchParams {
  q?: string;
  platforms?: Platform[];
  city?: string;
  minFollowers?: number;
  maxFollowers?: number;
  minRate?: number;
  maxRate?: number;
  verticals?: string[];
  page?: number;
  limit?: number;
}

function creatorSearchQuery(params: CreatorSearchParams): Record<string, string | number | undefined> {
  const q: Record<string, string | number | undefined> = {};
  if (params.q) q.q = params.q;
  if (params.city) q.city = params.city;
  if (params.page) q.page = params.page;
  if (params.limit) q.limit = params.limit;
  if (params.minFollowers != null) q.minFollowers = params.minFollowers;
  if (params.maxFollowers != null) q.maxFollowers = params.maxFollowers;
  if (params.minRate != null) q.minRate = params.minRate;
  if (params.maxRate != null) q.maxRate = params.maxRate;
  if (params.platforms?.length) q.platforms = params.platforms.join(',');
  if (params.verticals?.length) q.verticals = params.verticals.join(',');
  return q;
}

function mapCreatorFromApi(row: CreatorProfile & { location?: string }): CreatorProfile {
  return {
    ...row,
    location: row.location ?? (row as { city?: string }).city,
    portfolioItems: row.portfolioItems ?? [],
    categories: row.categories ?? [],
    platforms: row.platforms ?? [],
  };
}

export interface CreatorSearchResult {
  creators: CreatorProfile[];
  meta: { page: number; limit: number; total?: number; hasMore: boolean };
}

// D-14 — response shapes for the 4 backend-complete endpoints below (BrandF.md §87,
// wiki/errors/BRAND-BUG-TRACKER.md). Mirror DiscoveryDtos.java field-for-field.

export interface FeaturedCreatorSection {
  category: string;
  title: string;
  creators: CreatorProfile[];
}

export interface CreatorSuggestionItem {
  creator: CreatorProfile;
  matchScore: number;
  reasons: string[];
  estimatedReach: number;
  estimatedCost: number;
}

export interface SimilarCreator {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  totalFollowers: number;
  engagementRate: number;
  matchScore: number;
  matchReasons: string[];
}

/**
 * GET /creators/profile/:usernameOrId response (DiscoveryDtos.CreatorPublicProfileResponse) — the
 * DTO PR-1's fix reads from. Unlike GET /creators/:id (CreatorResponse), this has real
 * `completedCampaigns`/`avgRating`. `avgRating` is `null` (not `0`) when the creator has no
 * reviews yet — CreatorDiscoveryService.getPublicProfile's H-22 comment; never coerce to 0.
 */
export interface CreatorPublicProfile {
  id: string;
  username: string;
  displayName: string;
  bio: string | null;
  profilePhoto: string | null;
  coverPhoto: string | null;
  categories: string[];
  languages: string[];
  city: string | null;
  platforms: PlatformStats[];
  totalFollowers: number;
  engagementRate: number;
  scores: CreatorScoresSummary | null;
  rateMin: number | null;
  rateMax: number | null;
  currency: string | null;
  isVerified: boolean;
  discoverable: boolean;
  completedCampaigns: number;
  avgRating: number | null;
  saved: boolean | null;
}

export const creators = {
  /**
   * GET /creators?... — verified against CreatorController.search, which accepts
   * page/limit (defaults 1/20) and returns pagination meta via `result.page().meta()`
   * (same `ApiResponse.ok(items, meta)` envelope shape as GET /campaigns). Uses
   * `requestWithMeta` (not `request`) so callers can paginate — mirrors
   * `creatorCampaigns.browse`'s pattern on the creator side.
   */
  search: async (params: CreatorSearchParams = {}): Promise<CreatorSearchResult> => {
    if (!isLive()) {
      return mockOr<CreatorSearchResult>({
        creators: [],
        meta: { page: params.page ?? 1, limit: params.limit ?? 20, hasMore: false },
      });
    }
    const { data, meta } = await http.requestWithMeta<CreatorProfile[]>('GET', '/creators', {
      query: creatorSearchQuery(params),
    });
    return {
      creators: data.map(mapCreatorFromApi),
      meta: {
        page: meta?.page ?? params.page ?? 1,
        limit: meta?.limit ?? params.limit ?? 20,
        total: meta?.total,
        hasMore: Boolean(meta?.hasMore),
      },
    };
  },

  /** GET /creators/:id */
  get: async (id: string) => {
    if (!isLive()) return mockOr<CreatorProfile | null>(null);
    const row = await http.request<CreatorProfile>('GET', `/creators/${id}`);
    return mapCreatorFromApi(row);
  },

  /** POST /creators/:id/save */
  toggleSaved: (id: string, saved: boolean) =>
    isLive()
      ? http.request<{ saved: boolean }>('POST', `/creators/${id}/save`, { body: { saved } })
      : mockOr({ saved }),

  /** POST /creators/:id/invite */
  invite: (creatorId: string, campaignId: string, message?: string) =>
    isLive()
      ? http.request<{ collaborationId: string }>('POST', `/creators/${creatorId}/invite`, {
          body: { campaignId, message },
        })
      : mockOr({ collaborationId: 'col_new' }),

  /**
   * GET /creators/featured — CreatorController.featured (verified at
   * influora-api/.../web/CreatorController.java:127), returns DiscoveryDtos.FeaturedResponse. D-14.
   */
  featured: async (
    params: { category?: string; limit?: number } = {},
  ): Promise<{ featured: FeaturedCreatorSection[] }> => {
    if (!isLive()) return mockOr<{ featured: FeaturedCreatorSection[] }>({ featured: [] });
    const row = await http.request<{
      featured: { category: string; title: string; creators: (CreatorProfile & { location?: string })[] }[];
    }>('GET', '/creators/featured', { query: { category: params.category, limit: params.limit } });
    return {
      featured: row.featured.map((section) => ({
        ...section,
        creators: section.creators.map(mapCreatorFromApi),
      })),
    };
  },

  /**
   * POST /creators/suggestions — CreatorController.suggestions (verified at
   * .../CreatorController.java:136), body DiscoveryDtos.CreatorSuggestionRequest, returns
   * CreatorSuggestionsResponse. D-14.
   */
  suggestions: async (payload: {
    campaignGoals?: string;
    targetAudience?: string;
    budget?: number;
    platforms?: string[];
  }): Promise<{ suggestions: CreatorSuggestionItem[] }> => {
    if (!isLive()) return mockOr<{ suggestions: CreatorSuggestionItem[] }>({ suggestions: [] });
    const row = await http.request<{
      suggestions: {
        creator: CreatorProfile & { location?: string };
        matchScore: number;
        reasons: string[];
        estimatedReach: number;
        estimatedCost: number;
      }[];
    }>('POST', '/creators/suggestions', { body: payload });
    return {
      suggestions: row.suggestions.map((s) => ({ ...s, creator: mapCreatorFromApi(s.creator) })),
    };
  },

  /**
   * GET /creators/:username/similar — CreatorController.similar (verified at
   * .../CreatorController.java:144), returns DiscoveryDtos.SimilarCreatorsResponse. D-14.
   */
  similar: (username: string, limit?: number): Promise<{ similar: SimilarCreator[] }> =>
    isLive()
      ? http.request<{ similar: SimilarCreator[] }>('GET', `/creators/${encodeURIComponent(username)}/similar`, {
          query: { limit },
        })
      : mockOr<{ similar: SimilarCreator[] }>({ similar: [] }),

  /**
   * GET /creators/profile/:usernameOrId — CreatorController.getPublicProfile (verified at
   * .../CreatorController.java:159), returns DiscoveryDtos.CreatorPublicProfileResponse with real
   * `completedCampaigns`/`avgRating` (PR-1, BrandF.md §87 — GET /creators/:id's CreatorResponse
   * has neither field, which is why brand-creator-profile.tsx used to hardcode both to 0). D-14.
   */
  getProfile: (usernameOrId: string): Promise<CreatorPublicProfile | null> =>
    isLive()
      ? http.request<CreatorPublicProfile>('GET', `/creators/profile/${encodeURIComponent(usernameOrId)}`)
      : mockOr<CreatorPublicProfile | null>(null),
};

// ---------------------------------------------------------------------------
// Campaign templates (BR-14 Phase 1) — CampaignTemplateController @ /campaign-templates.
// Read is free to every plan tier (4 SYSTEM presets are seeded); only POST (save-as-template,
// not built in this pass) carries @RequiresPlan. No FE plan gate needed for list/get.
// ---------------------------------------------------------------------------

/** GET /campaign-templates[/:id] — CampaignTemplateDtos.CampaignTemplateResponse (@JsonInclude NON_NULL). */
export interface CampaignTemplateResponse {
  id: string;
  name: string;
  description?: string | null;
  category?: string | null;
  scope: 'SYSTEM' | 'CUSTOM' | string;
  workspaceId?: string | null;
  createdBy?: string | null;
  campaignType?: string | null;
  budgetMin?: number | null;
  budgetMax?: number | null;
  platforms?: string[];
  contentTypes?: string[];
  objectives?: string[];
  requirements?: string[];
  hashtags?: string[];
  targetAudience?: TargetAudience | null;
  brandGuidelines?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export const campaignTemplates = {
  /** GET /campaign-templates — the 4 SYSTEM presets plus any CUSTOM templates for the workspace. */
  list: () =>
    isLive()
      ? http.request<CampaignTemplateResponse[]>('GET', '/campaign-templates')
      : mockOr<CampaignTemplateResponse[]>([]),

  /** GET /campaign-templates/:id */
  get: (templateId: string) =>
    isLive()
      ? http.request<CampaignTemplateResponse>('GET', `/campaign-templates/${templateId}`)
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Templates are not available in mock mode')),

  /**
   * POST /campaign-templates (CampaignTemplateController.java:54, @RequiresPlan CAMPAIGN_TEMPLATES)
   * — save an existing campaign as a reusable CUSTOM template for the workspace.
   */
  create: (payload: { campaignId: string; name: string; description?: string }) =>
    isLive()
      ? http.request<CampaignTemplateResponse>('POST', '/campaign-templates', { body: payload })
      : mockOr<CampaignTemplateResponse>({ id: 'tpl_new', name: payload.name } as CampaignTemplateResponse),

  /** DELETE /campaign-templates/:id (CampaignTemplateController.java:62) — remove a CUSTOM template. */
  remove: (templateId: string) =>
    isLive()
      ? http.request<{ ok: boolean }>('DELETE', `/campaign-templates/${templateId}`)
      : mockOr<{ ok: boolean }>({ ok: true }),
};

// ---------------------------------------------------------------------------
// Deals (collaborations) — unified for both brand + creator UIs
// ---------------------------------------------------------------------------

export type DealStatusFilter =
  | 'all'
  | 'new'           // creator: incoming proposals; brand: outgoing pending
  | 'negotiating'
  | 'contracted'
  | 'in_progress'
  | 'review'
  | 'completed'
  | 'disputed';     // CR-26 — CANCELLED + DISPUTED; previously selected by no filter at all

/**
 * CR-13 — what actually goes on the wire as `?status=`.
 *
 * `DealService.statusesForFilter` accepts a comma-separated union, which is how the creator
 * "Active" chip asks for `contracted,in_progress,review` in one request. It used to send the
 * single value `in_progress`, which the server maps to `[IN_PROGRESS]` only — so a signed,
 * CONTRACTED deal never came back and the Active tab read "Nothing active." while an active
 * deal existed. Kept distinct from `DealStatusFilter` so the single-value union stays the
 * vocabulary for chips, empty states and local predicates.
 */
export type DealStatusQuery = DealStatusFilter | `${string},${string}`;

export interface Deal {
  id: string;
  campaignId: string;
  campaignName: string;
  counterpartyId: string;     // creatorId for brand, brandId for creator
  /**
   * The counterparty's CreatorProfile id — distinct from `counterpartyId`, which is the
   * creator's User id. Frontend profile pages (e.g. brand-creator-profile.tsx) navigate/match
   * on CreatorProfile id, not User id — see DealDtos.DealResponse.counterpartyProfileId
   * (influora-api DealDtos.java:38). Null when the counterparty is a brand (viewer is CREATOR).
   */
  counterpartyProfileId?: string | null;
  counterpartyName: string;
  counterpartyAvatar?: string;
  counterpartyHandle?: string;
  /**
   * PR-2 (BrandF.md §83c/§105 VER-1) — {@code DealDtos.DealResponse.counterpartyVerificationStatus}
   * (DealDtos.java:39). The counterparty BRAND's real `Workspace.verificationStatus` when the
   * viewer is a CREATOR; always null when the viewer is a BRAND (the counterparty is a creator,
   * a separate signal — `identityKycStatus` — not this field). Fixes the M-1 defect where
   * creators were shown an unconditional "Verified Brand" badge regardless of actual status.
   */
  counterpartyVerificationStatus?: VerificationStatus | null;
  status: CollaborationStatus;
  dealValue: number;
  currency: 'INR' | 'USD';
  lastMessage?: string;
  lastMessageAt?: string;     // ISO
  unreadCount: number;
  deliverablesDone: number;
  deliverablesTotal: number;
  nextDeadline?: string;      // ISO
  contractId?: string;
  contractStatus?: ContractStatus;
  escrowFunded: boolean;
}

export const deals = {
  /**
   * GET /deals?role=brand|creator&status=...
   * Single endpoint that powers brand Deal Room list AND creator unified Deals page.
   */
  list: (role: Role, status: DealStatusQuery = 'all') =>
    isLive()
      ? http.request<Deal[]>('GET', '/deals', { role, query: { status } })
      : mockOr<Deal[]>([]),

  /** GET /deals/:id */
  get: (role: Role, id: string) =>
    isLive()
      ? http.request<Deal>('GET', `/deals/${id}`, { role })
      : mockOr<Deal | null>(null),

  /**
   * POST /deals/:id/accept — brand or creator accepts whichever offer is currently on the
   * table. `role` defaults to 'creator' to preserve existing call sites; pass 'brand' for
   * brand-side accept (backend B-4: dual-role, mirrors `counter()`'s auth/scoping — brand
   * can no longer only counter). Backend rejects with `CANNOT_ACCEPT_OWN_OFFER` (409) if the
   * caller's own party made the last offer — only the counterparty may accept it.
   */
  accept: (id: string, role: Role = 'creator') =>
    isLive()
      ? http.request<Deal>('POST', `/deals/${id}/accept`, { role })
      : mockOr<{ id: string; status: CollaborationStatus }>({ id, status: 'TERMS_AGREED' }),

  /**
   * POST /deals/:id/reject — brand or creator rejects/withdraws from the deal. `role`
   * defaults to 'creator' to preserve existing call sites; pass 'brand' for brand-side
   * reject (backend B-4: dual-role, mirrors `counter()`'s auth/scoping).
   */
  reject: (id: string, reason?: string, role: Role = 'creator') =>
    isLive()
      ? http.request<{ ok: true }>('POST', `/deals/${id}/reject`, {
          role,
          body: { reason },
        })
      : mockOr({ ok: true as const }),

  /**
   * POST /deals/:id/counter. Pass a fresh `idempotencyKey` per user action — without it the
   * server falls back to a key derived from `dealId + amount`, so two legitimate counters at
   * the SAME amount on the same deal collide and the second silently no-ops (Kabir).
   *
   * `deadline` and `usageRights` were added 2026-07-26 to match `deals.create`'s payload. Before
   * that, CounterRequest carried amount/message/deliverables only, so every caller that let a
   * user revise a deadline or usage rights concatenated them into `message` as prose the server
   * could not read. Send them as fields now — `usageRights` is persisted onto the deal exactly as
   * `deals.create` persists it, and `deadline` lands in the proposal message metadata.
   */
  counter: (
    id: string,
    payload: {
      amount: number;
      message?: string;
      deliverables?: Array<{ type: string; qty: number }>;
      deadline?: string;
      usageRights?: string;
    },
    role: Role = 'creator',
    idempotencyKey?: string,
  ) =>
    isLive()
      ? http.request<Deal>('POST', `/deals/${id}/counter`, { role, body: payload, idempotencyKey })
      : mockOr<{ id: string }>({ id }),

  /**
   * POST /deals — brand sends a PRICED offer (DealController.create → DealService.createProposal).
   *
   * Relationship to `creators.invite`: both are campaign-scoped and both create the same
   * `Collaboration` row keyed on (campaignId, creatorId), so they 409 `COLLABORATION_EXISTS`
   * against each other. They differ only in fidelity — invite lands `INVITED` with no terms and
   * fires no notification; this lands `IN_NEGOTIATION` with `agreedRate` set, writes a proposal
   * message, and fires `ProposalSentEvent`. Callers pick one per submit, never both.
   *
   * `creatorId` is a CreatorProfile id (what `creators.search` returns), not a User id — a userId
   * is tolerated server-side but profile id is the contract.
   *
   * Only `usageRights` is persisted onto the Collaboration; `deliverables` and `deadline` live in
   * the proposal message's metadata. `exclusivity` was removed 2026-07-26 — the server accepted
   * and silently discarded it, so offering the toggle told brands we enforce something we don't.
   *
   * No `Idempotency-Key`: the (campaignId, creatorId) unique constraint already prevents a
   * duplicate row, so a double-submit is a 409, not corruption (explicit CEO call not to
   * gold-plate this before launch).
   */
  create: (payload: {
    campaignId: string;
    creatorId: string;
    amount: number;
    deliverables?: Array<{ type: string; qty: number }>;
    deadline?: string;
    usageRights?: string;
    message?: string;
  }) =>
    isLive()
      ? http.request<Deal>('POST', '/deals', { body: payload })
      : mockOr<{ id: string }>({ id: 'd_new' }),
};

// ---------------------------------------------------------------------------
// Messages (deal room chat)
// ---------------------------------------------------------------------------

export type MessageKind =
  | 'text'
  | 'system'
  | 'proposal'
  | 'contract'
  | 'deliverable'
  | 'payment'
  | 'shipment';

export interface DealMessage {
  id: string;
  dealId: string;
  kind: MessageKind;
  senderId: string;
  senderType: 'brand' | 'creator' | 'system';
  content?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
  readBy: string[];
}

// CR-31 — deal-message stream reconnect tuning. Backoff doubles from BASE to MAX; a
// connection that holds for STABLE_MS is treated as proof the backend recovered and resets
// the ladder. MAX is deliberately well under a typical proxy idle-timeout so a room that
// goes quiet overnight still reattaches on its own rather than waiting for a page reload.
const STREAM_RECONNECT_BASE_MS = 1_000;
const STREAM_RECONNECT_MAX_MS = 30_000;
const STREAM_STABLE_MS = 10_000;

/** HTTP statuses on which reconnecting the message stream cannot possibly help. */
const TERMINAL_STREAM_STATUSES: readonly number[] = [401, 403, 404];

export const messages = {
  /** GET /deals/:dealId/messages?before= */
  list: (role: Role, dealId: string, before?: string) =>
    isLive()
      ? http.request<DealMessage[]>('GET', `/deals/${dealId}/messages`, { role, query: { before } })
      : mockOr<DealMessage[]>([]),

  /** POST /deals/:dealId/messages */
  send: (role: Role, dealId: string, content: string, kind: MessageKind = 'text') =>
    isLive()
      ? http.request<DealMessage>('POST', `/deals/${dealId}/messages`, {
          role,
          body: { content, kind },
          idempotencyKey: `${dealId}-${Date.now()}`,
        })
      : mockOr<DealMessage>({
          id: `m_${Date.now()}`,
          dealId,
          kind,
          senderId: role === 'brand' ? 'u_1' : 'cr_1',
          senderType: role,
          content,
          createdAt: new Date().toISOString(),
          readBy: [],
        }),

  /** POST /deals/:dealId/messages/read */
  markRead: (role: Role, dealId: string) =>
    isLive()
      ? http.request<{ ok: true }>('POST', `/deals/${dealId}/messages/read`, { role })
      : mockOr({ ok: true as const }),

  /**
   * GET /deals/:dealId/messages/stream — realtime deal-chat SSE.
   *
   * Backend contract (locked, Priya direct assignment 2026-07-18, see
   * SHARED_CONTEXT.md "Realtime messaging for brand-chat"): named event
   * `deal-message`, `data:` = the SAME DealMessageResponse shape `messages.list`
   * already returns — mapped 1:1 onto `DealMessage` here, no parallel type.
   *
   * Must be fetch-based SSE with a standard `Authorization: Bearer <token>`
   * header, NOT raw `EventSource` (EventSource can't send headers and the
   * token must never ride in the URL). Mirrors `src/hooks/useMeeraStream.ts`'s
   * fetch + ReadableStream + manual SSE-frame-parsing approach; unlike that
   * hook this is a plain GET with no request body and no stream token — it
   * uses the same ordinary role token (`brand_token`/`creator_token`) every
   * other brand/creator deal request uses.
   *
   * Returns a handle whose `close()` aborts the underlying fetch and cancels any
   * pending reconnect — callers must call it on deal switch and on unmount to avoid
   * leaking connections or letting a stale deal's stream write into the newly
   * selected one.
   *
   * Never throws synchronously and never rejects the caller's render path:
   * connection failures, non-OK responses, and stream read errors all route
   * through `handlers.onError` (optional) so a failed stream degrades to the
   * existing fetch-on-load (`messages.list`) path.
   *
   * ---------------------------------------------------------------------------
   * CR-31 — this reconnects, and a clean close is a disconnect, not an exit
   * ---------------------------------------------------------------------------
   * Dropping `EventSource` for fetch (necessary — `EventSource` cannot send an
   * `Authorization` header and the token must never ride in the URL) also dropped the
   * automatic reconnect `EventSource` gives for free, and that was never reimplemented.
   * Worse, the original read loop treated `done` as a normal return: when the server
   * closed the stream cleanly — a Caddy idle-timeout, an API restart, any proxy hiccup —
   * this function returned having called **nothing at all**. Not `onError`, not a log.
   * The room went permanently deaf with no trace and no recovery short of switching
   * deals, which silently undid CR-08's entire purpose.
   *
   * So: every way a connection can end now schedules a reconnect with exponential
   * backoff + jitter, and `onStatusChange` exists so the room can say so out loud
   * rather than looking healthy while receiving nothing.
   *
   * The stream carries no `Last-Event-ID` replay, so frames published during a gap are
   * unrecoverable from the transport. `onReconnect` is the caller's cue to refetch —
   * reconnecting without it would resume future frames while silently keeping the hole.
   */
  stream: (role: Role, dealId: string, handlers: DealMessageStreamHandlers): DealMessageStreamHandle => {
    const controller = new AbortController();
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    /** Consecutive failed/ended connections. Reset once a connection proves stable. */
    let attempt = 0;
    /** Has a connection ever opened? Distinguishes a first connect from a reconnect. */
    let everOpened = false;
    /** At most one 401-driven token refresh per connection generation. */
    let authRetried = false;

    const stopped = () => controller.signal.aborted;

    const scheduleReconnect = () => {
      if (stopped()) return;
      attempt += 1;
      const ceiling = Math.min(
        STREAM_RECONNECT_BASE_MS * 2 ** (attempt - 1),
        STREAM_RECONNECT_MAX_MS,
      );
      // Jittered across the top half of the window: when one API restart drops every open
      // room at once, they must not all retry on the same tick and restart the stampede.
      const delay = ceiling / 2 + Math.random() * (ceiling / 2);
      handlers.onStatusChange?.('reconnecting');
      retryTimer = setTimeout(() => void connect(), delay);
    };

    const connect = async (): Promise<void> => {
      if (stopped()) return;

      let response: Response;
      try {
        const token = localStorage.getItem(TOKEN_KEYS[role]);
        response = await fetch(`${API_BASE_URL}/deals/${dealId}/messages/stream`, {
          method: 'GET',
          headers: {
            Accept: 'text/event-stream',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          credentials: 'include',
          signal: controller.signal,
        });
      } catch (err) {
        if (stopped()) return;
        handlers.onError?.(
          err instanceof Error ? err : new Error('Deal message stream connection failed'),
        );
        scheduleReconnect();
        return;
      }

      if (stopped()) return;

      // 401 is the one rejection worth an instant retry: the access token simply expired
      // while the room sat open. Ordinary requests get this for free from the H-19
      // interceptor, which this raw fetch bypasses — so drive the same refresh explicitly.
      // Guarded to once per generation: a 401 that survives a successful refresh means the
      // session is genuinely gone, and falls through to the terminal branch below.
      if (response.status === 401 && !authRetried) {
        authRetried = true;
        if (await http.bootstrap(role)) {
          await connect();
          return;
        }
      }

      if (!response.ok || !response.body) {
        const error = new Error(`Deal message stream rejected (HTTP ${response.status})`);
        handlers.onError?.(error);
        // 401/403/404 are verdicts, not blips. Retrying cannot change the answer and would
        // hammer the API for as long as the room stays open, so this is where we stop.
        if (TERMINAL_STREAM_STATUSES.includes(response.status)) {
          handlers.onStatusChange?.('closed');
        } else {
          scheduleReconnect();
        }
        return;
      }

      const openedAt = Date.now();
      const isReconnect = everOpened;
      everOpened = true;
      authRetried = false;
      handlers.onStatusChange?.('open');
      handlers.onOpen?.();
      // Anything published while we were down is gone — no replay on this transport. The
      // caller refetches here or keeps the hole forever.
      if (isReconnect) handlers.onReconnect?.();

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          // Frames are separated by a blank line (\n\n or \r\n\r\n) — same
          // framing useMeeraStream's SSE reader uses.
          for (;;) {
            const sep = buffer.search(/\r?\n\r?\n/);
            if (sep === -1) break;
            const rawFrame = buffer.slice(0, sep);
            buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, '');

            const frame = parseDealMessageSseFrame(rawFrame);
            if (!frame || frame.event !== 'deal-message') continue; // heartbeat/other events
            try {
              const dto = JSON.parse(frame.data) as DealMessage;
              handlers.onMessage(dto);
            } catch {
              // Malformed event payload — skip this frame only, non-fatal.
              console.debug('[messages.stream] malformed deal-message payload:', frame.data);
            }
          }
        }
      } catch (err) {
        if (stopped()) return;
        handlers.onError?.(
          err instanceof Error ? err : new Error('Deal message stream interrupted'),
        );
      }

      if (stopped()) return;
      // The connection ended — cleanly via `done`, or by the read error just reported.
      // A clean end is NOT success: the server closed a stream that is supposed to stay
      // open, so the room is deaf from here. This is the exact path that used to return
      // in silence (CR-31).
      //
      // Only a connection that held for a while counts as proof the backend is healthy;
      // resetting on any open at all would turn an accept-then-immediately-close server
      // into a tight reconnect loop at the base delay.
      if (Date.now() - openedAt >= STREAM_STABLE_MS) attempt = 0;
      scheduleReconnect();
    };

    void connect();

    return {
      // No 'closed' status emitted here on purpose: this path is the caller closing its own
      // stream (deal switch, unmount), it already knows, and telling it would mean a state
      // update from an effect cleanup for no one's benefit.
      close: () => {
        controller.abort();
        if (retryTimer) clearTimeout(retryTimer);
      },
    };
  },
};

/**
 * CR-31 — transport state of a deal-message stream.
 *
 * `'closed'` means the stream gave up and will not retry (a 401/403/404 verdict). It is
 * never emitted for a caller-initiated `close()` — the caller already knows.
 */
export type DealMessageStreamStatus = 'open' | 'reconnecting' | 'closed';

export interface DealMessageStreamHandlers {
  /** Called for each `deal-message` SSE event with the parsed DealMessage DTO. */
  onMessage: (message: DealMessage) => void;
  /** Called every time a connection is established (headers received), including reconnects. */
  onOpen?: () => void;
  /**
   * Called on connection failure, a non-OK response, or a stream read error.
   * Never throws internally — the caller decides whether/how to degrade.
   *
   * CR-31: this fires on every failed attempt, so it is a diagnostic channel, not a
   * "the room is broken" signal. Drive user-facing state from `onStatusChange`.
   */
  onError?: (error: Error) => void;
  /**
   * CR-31 — transport state changes, so the room can show that it has stopped receiving
   * instead of looking healthy while deaf.
   */
  onStatusChange?: (status: DealMessageStreamStatus) => void;
  /**
   * CR-31 — a connection opened after a previous one dropped. **Refetch here.** This
   * transport has no `Last-Event-ID` replay, so frames published during the gap are gone;
   * reconnecting without refetching resumes future messages and keeps the hole.
   */
  onReconnect?: () => void;
}

export interface DealMessageStreamHandle {
  /** Aborts the underlying fetch, cancels any pending reconnect, and stops reading. */
  close: () => void;
}

interface DealMessageSseFrame {
  event: string;
  data: string;
}

/**
 * Parse a single raw SSE frame (the text between two blank lines) per the SSE
 * spec — mirrors `src/hooks/useMeeraStream.ts`'s `parseSseFrame`: `:`-prefixed
 * lines are comments (heartbeats), multiple `data:` lines join with `\n`, one
 * optional leading space after the field colon is stripped. Returns `null`
 * for a comment-only/heartbeat frame.
 */
function parseDealMessageSseFrame(rawFrame: string): DealMessageSseFrame | null {
  let event = 'message';
  const dataLines: string[] = [];

  for (const rawLine of rawFrame.split(/\r?\n/)) {
    if (rawLine === '' || rawLine.startsWith(':')) continue;
    const colon = rawLine.indexOf(':');
    const field = colon === -1 ? rawLine : rawLine.slice(0, colon);
    let value = colon === -1 ? '' : rawLine.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') event = value;
    else if (field === 'data') dataLines.push(value);
  }

  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join('\n') };
}

// ---------------------------------------------------------------------------
// Shipments (product-seeding deals — Priya's design 2026-07-24,
// wiki/decisions/shipment-backend-design-2026-07-24.md)
// ---------------------------------------------------------------------------

/**
 * Server-side lifecycle. `AWAITING_ADDRESS` is synthetic — returned by GET
 * when no `Shipment` row exists yet for the collaboration (the row is
 * lazy-created on first address submit). Named `ShipmentApiStatus` (not
 * `ShipmentStatus`) to avoid colliding with the unrelated local UI enum of
 * the same name in `src/components/shared/shipment-card.tsx`.
 */
export type ShipmentApiStatus =
  | 'AWAITING_ADDRESS'
  | 'ADDRESS_PROVIDED'
  | 'SHIPPED'
  | 'RECEIVED'
  | 'DAMAGED';

export type ShipmentCondition = 'GOOD' | 'DAMAGED';

/**
 * `GET /deals/:id/shipment` response. Mirrors the `Shipment` entity (§1 of
 * the design doc). Every field besides `status` is nullable until the
 * corresponding transition happens (address fields until `ADDRESS_PROVIDED`,
 * carrier/tracking until `SHIPPED`, `receivedCondition`/`conditionNote` until
 * receipt is confirmed) — including the synthetic `AWAITING_ADDRESS` case,
 * where the whole record besides `status` is absent.
 */
export interface ShipmentApiRecord {
  id?: string;
  collaborationId?: string;
  status: ShipmentApiStatus;
  recipientName?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  city?: string | null;
  state?: string | null;
  pincode?: string | null;
  phone?: string | null;
  productName?: string | null;
  carrier?: string | null;
  trackingNumber?: string | null;
  trackingUrl?: string | null;
  conditionNote?: string | null;
  receivedCondition?: ShipmentCondition | null;
  createdAt?: string;
  updatedAt?: string;
  /** Brand-supplied ETA (creator-shipment-eta-0804), ISO date `YYYY-MM-DD`; null until shipped with a date. */
  estimatedDelivery?: string | null;
}

/** Body for `POST /deals/:id/shipping-address` — creator-supplied delivery address. */
export interface ShipmentAddressSubmission {
  recipientName: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  state: string;
  pincode: string;
  phone: string;
}

/**
 * Body for `POST /deals/:id/shipment/confirm-receipt`. The design doc specifies
 * exactly two conditions (`GOOD`/`DAMAGED`) on the wire; the creator-facing
 * `ReceiptConfirmation` component additionally offers a `wrong_item` option with
 * no server-side equivalent — callers must fold `wrong_item` into `DAMAGED` and
 * carry the distinction in `note` (e.g. prefix "Wrong item received: ..."), since
 * the doc is silent on a third condition and DAMAGED is the safest server shape.
 */
export interface ShipmentReceiptSubmission {
  condition: ShipmentCondition;
  note?: string;
}

/**
 * Body for `POST /deals/:id/shipment` — brand-only, marks the deal SHIPPED.
 * Field names match `ShipmentDtos.MarkShippedRequest` (influora-api
 * `web/dto/shipment/ShipmentDtos.java`) exactly: `carrier` (not `courier`),
 * `trackingNumber`, optional `trackingUrl`, and `productName` (a single
 * string — the backend has no `items[]` concept).
 */
export interface ShipmentMarkShippedSubmission {
  carrier: string;
  trackingNumber: string;
  trackingUrl?: string;
  productName: string;
  /** Optional brand-supplied ETA (creator-shipment-eta-0804), ISO date `YYYY-MM-DD`. */
  estimatedDelivery?: string;
}

export const shipments = {
  /** GET /deals/:id/shipment — dual-role (brand or creator); returns synthetic AWAITING_ADDRESS if no row yet. */
  get: (role: Role, dealId: string) =>
    isLive()
      ? http.request<ShipmentApiRecord>('GET', `/deals/${dealId}/shipment`, { role })
      : mockOr<ShipmentApiRecord>({ status: 'AWAITING_ADDRESS' }),

  /** POST /deals/:id/shipping-address — creator only. Rejected (409 SHIPMENT_ALREADY_SHIPPED) once SHIPPED. */
  submitAddress: (dealId: string, address: ShipmentAddressSubmission) =>
    isLive()
      ? http.request<ShipmentApiRecord>('POST', `/deals/${dealId}/shipping-address`, {
          role: 'creator',
          body: address,
        })
      : mockOr<ShipmentApiRecord>({
          status: 'ADDRESS_PROVIDED',
          ...address,
        }),

  /** POST /deals/:id/shipment/confirm-receipt — creator only. Rejected (409 SHIPMENT_NOT_SHIPPED) unless currently SHIPPED. */
  confirmReceipt: (dealId: string, body: ShipmentReceiptSubmission) =>
    isLive()
      ? http.request<ShipmentApiRecord>('POST', `/deals/${dealId}/shipment/confirm-receipt`, {
          role: 'creator',
          body,
        })
      : mockOr<ShipmentApiRecord>({
          status: body.condition === 'GOOD' ? 'RECEIVED' : 'DAMAGED',
          receivedCondition: body.condition,
          conditionNote: body.note ?? null,
        }),

  /** POST /deals/:id/shipment — brand only. Transitions ADDRESS_PROVIDED -> SHIPPED (DealController.java:199). */
  markShipped: (dealId: string, body: ShipmentMarkShippedSubmission) =>
    isLive()
      ? http.request<ShipmentApiRecord>('POST', `/deals/${dealId}/shipment`, {
          role: 'brand',
          body,
        })
      : mockOr<ShipmentApiRecord>({
          status: 'SHIPPED',
          carrier: body.carrier,
          trackingNumber: body.trackingNumber,
          trackingUrl: body.trackingUrl ?? null,
          productName: body.productName,
          estimatedDelivery: body.estimatedDelivery ?? null,
        }),
};

// ---------------------------------------------------------------------------
// Contracts
// ---------------------------------------------------------------------------

/**
 * Payment milestone as returned inside `ContractResponse.milestones[]`
 * (backend `payment_milestones` table, V10:24) and as sent in
 * `ContractGenerateRequest.milestones[]` (`MoneyDtos.java:189`).
 */
export interface ContractMilestone {
  id?: string;
  sequenceNo: number;
  description: string;
  amount: number;
  dueDate?: string | null;
  status?: string;
}

/**
 * Mirrors the backend `ContractResponse` record exactly (`MoneyDtos.java:155`,
 * `Contract.java:19`). Contract flow spec: wiki/build/contract-flow-architecture-2026-07-23.md §3/§5.1.
 * `totalAmount` is always server-summed from `milestones` — never trust a
 * client-held total. `brandSignedAt`/`creatorSignedAt` are the two
 * independent timestamps the FE must read to distinguish "awaiting brand"
 * from "awaiting creator" — `status` alone collapses both into
 * `PENDING_SIGNATURES` (architecture doc §4).
 */
export interface ContractApiRecord {
  id: string;
  collaborationId: string;
  version?: number;
  status: ContractStatus;
  totalAmount: number;
  currency: string;
  pdfR2Key?: string | null;
  brandSignedAt: string | null;
  creatorSignedAt: string | null;
  milestones: ContractMilestone[];
  effectiveDate?: string | null;
  expirationDate?: string | null;
  createdAt?: string;
}

/** Body for `POST /contracts` — `ContractGenerateRequest` (`MoneyDtos.java:188`). */
export interface ContractGeneratePayload {
  collaborationId: string;
  milestones: Array<{
    sequenceNo: number;
    description: string;
    amount: number;
    dueDate?: string;
  }>;
}

export const contracts = {
  /**
   * GET /contracts?dealId= — role-aware (`ContractController.java:46`). Returns
   * `[]` when no contract exists for the deal yet — never 404. This is the
   * honest "not created yet" signal (architecture doc §5.2); do not build/call
   * a `GET /deals/:id/contract` route.
   */
  list: (role: Role, dealId?: string) =>
    isLive()
      ? http.request<ContractApiRecord[]>('GET', '/contracts', { role, query: { dealId } })
      : mockOr<ContractApiRecord[]>([]),

  /** GET /contracts/:id — full contract incl. milestones + signature timestamps. */
  get: (role: Role, id: string) =>
    isLive()
      ? http.request<ContractApiRecord>('GET', `/contracts/${id}`, { role })
      : mockOr<ContractApiRecord | null>(null),

  /**
   * POST /contracts — brand generates a contract for a collaboration
   * (`OWNER/ADMIN/MANAGER` only; server re-sums `totalAmount` from
   * `milestones`, never trusts a client total). Body shape is
   * `{ collaborationId, milestones }` — NOT `{ dealId }`.
   */
  generate: (payload: ContractGeneratePayload) =>
    isLive()
      ? http.request<ContractApiRecord>('POST', '/contracts', { body: payload })
      : mockOr<ContractApiRecord>({
          id: 'CTR_new',
          collaborationId: payload.collaborationId,
          status: 'DRAFT',
          totalAmount: payload.milestones.reduce((sum, m) => sum + m.amount, 0),
          currency: 'INR',
          brandSignedAt: null,
          creatorSignedAt: null,
          milestones: payload.milestones,
        }),

  /**
   * POST /contracts/:id/sign — signer role is server-derived from the JWT
   * (`role` in the body is ignored for the creator path); returns the
   * post-sign contract so the caller can re-derive the honest UI state from
   * the real `brandSignedAt`/`creatorSignedAt` timestamps.
   */
  sign: (role: Role, id: string, signature: { name: string; agreedAt: string }) =>
    isLive()
      ? http.request<ContractApiRecord>('POST', `/contracts/${id}/sign`, {
          role,
          body: signature,
        })
      : mockOr<ContractApiRecord>({
          id,
          collaborationId: '',
          status: role === 'creator' ? 'ACTIVE' : 'PENDING_SIGNATURES',
          totalAmount: 0,
          currency: 'INR',
          brandSignedAt: new Date().toISOString(),
          creatorSignedAt: role === 'creator' ? new Date().toISOString() : null,
          milestones: [],
        }),

  /**
   * GET /contracts/:id/pdf-download-url — mints a fresh short-lived presigned
   * R2 GET link. 404 `CONTRACT_PDF_NOT_READY` until both parties have signed
   * and the PDF has been generated — that 404 is legitimate, surface it as
   * "PDF available after both sign" rather than retrying silently.
   */
  pdfDownloadUrl: (role: Role, id: string) =>
    isLive()
      ? http.request<{ url: string }>('GET', `/contracts/${id}/pdf-download-url`, { role })
      : mockOr<{ url: string }>({ url: '' }),
};

// ---------------------------------------------------------------------------
// Deliverables
// ---------------------------------------------------------------------------

/** DeliverableFile — DPF-1 backend response (DeliverableDetailResponse.DeliverableFileDto) */
export interface DeliverableFile {
  id: string;
  fileType: 'IMAGE' | 'VIDEO';
  fileName: string;
  url: string;          // presigned R2 URL (15-min expiry)
  thumbnailUrl: string | null;
  fileSize: number;
}

/** DeliverableDetail — DPF-1 backend response (DeliverableDetailResponse) */
export interface DeliverableDetail {
  id: string;
  title: string;
  status: DeliverableStatus;
  versionNumber: number;
  files: DeliverableFile[];
  caption: string | null;
  hashtags: string[];
  creatorNotes: string | null;
  reviewNotes: string | null;
  submittedAt: string;
  canApprove: boolean;
  canRequestRevision: boolean;
  /** D-9 (BrandF.md §25): same canReview gate as canApprove/canRequestRevision. */
  canReject: boolean;
}

/**
 * Deliverable-level brand-safety advisory review — Brand Surface Audit fix #3
 * (wiki/reports/brand-feature-audit.md item 3). Field-for-field CONFIRMED
 * against Vikram's real `DeliverableSafetyDtos.java` by Priya's contract
 * reconciliation (wiki/build/brand-fixes-priya-review.md — verdict/status
 * enum casing exact, `SafetyCheck{id,label,status,detail}` exact,
 * `score`/`computedAt` nullable+omitted on NON_NULL, both already optional
 * here so their absence is valid) and red-teamed by Kabir
 * (wiki/build/brand-fixes-kabir-review.md — no cross-party leak, no IDOR,
 * verdict is server-computed and cannot be steered by model output). `detail`
 * IS model-generated free text about the deliverable's own caption (Kabir F1)
 * — render it as plain text only, never `dangerouslySetInnerHTML`
 * (Kabir F2; see DeliverableSafetyReviewCard).
 */
export type DeliverableSafetyVerdict = 'PASS' | 'REVIEW' | 'FAIL';
export type DeliverableSafetyCheckStatus = 'PASS' | 'FAIL' | 'WARNING';

export interface DeliverableSafetyCheck {
  id: string;
  label: string;
  status: DeliverableSafetyCheckStatus;
  /** Optional — human-readable rationale, if the backend's classifier returns one. */
  detail?: string;
}

export interface DeliverableSafetyReview {
  overallVerdict: DeliverableSafetyVerdict;
  checks: DeliverableSafetyCheck[];
  /** Optional — 0-100; not confirmed whether the backend reuses CreatorScoresResponse.brandSafetyScore's scale. */
  score?: number | null;
  /** Optional — set when the classifier hasn't run yet for this deliverable version (e.g. async job pending). */
  computedAt?: string | null;
}

export const deliverables = {
  /** GET /deals/:dealId/deliverables */
  list: (role: Role, dealId: string) =>
    isLive()
      ? http.request('GET', `/deals/${dealId}/deliverables`, { role })
      : mockOr([]),

  /** GET /deliverables/:id — DPF-1 (brand detail viewer) */
  getDetail: (id: string) =>
    isLive()
      ? http.request<DeliverableDetail>('GET', `/deliverables/${id}`)
      : mockOr<DeliverableDetail>({
          id,
          title: 'Instagram Reel - Product Launch',
          status: 'SUBMITTED',
          versionNumber: 1,
          files: [
            {
              id: 'file-1',
              fileType: 'VIDEO',
              fileName: 'reel-draft-v1.mp4',
              url: 'https://mock-r2.cloudflare.com/deliverable-video-1.mp4',
              thumbnailUrl: 'https://mock-r2.cloudflare.com/deliverable-video-1-thumb.jpg',
              fileSize: 15728640, // 15 MB
            },
          ],
          caption: 'Check out this amazing new product launch! 🚀 #NewProduct #Launch2026',
          hashtags: ['#NewProduct', '#Launch2026', '#Influora'],
          creatorNotes: 'Added trending music and quick cuts per your brief',
          reviewNotes: null,
          submittedAt: new Date().toISOString(),
          canApprove: true,
          canRequestRevision: true,
          canReject: true,
        }),

  /**
   * POST /creator/deliverables/:id/submit — SubmitRequest(finalCaption, hashtags, notes), all
   * optional (CreatorDeliverableDtos.java:50). The server requires files to have been uploaded
   * FIRST via `creatorDeliverables.upload` (the multipart /upload route) — submitting with no
   * uploaded version returns 400 NO_CONTENT. There is no `fileUrls` field on this endpoint.
   */
  submit: (id: string, payload: { finalCaption?: string; hashtags?: string[]; notes?: string } = {}) =>
    isLive()
      ? http.request<{ deliverableId: string; status: DeliverableStatus; message?: string }>(
          'POST',
          `/creator/deliverables/${id}/submit`,
          { role: 'creator', body: payload },
        )
      : mockOr({ deliverableId: id, status: 'SUBMITTED' as DeliverableStatus }),

  /** POST /deliverables/:id/approve  (brand) */
  approve: (id: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/approve`)
      : mockOr({ status: 'APPROVED' as DeliverableStatus }),

  /** POST /deliverables/:id/revise  (brand) */
  requestRevision: (id: string, feedback: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/revise`, {
          body: { feedback },
        })
      : mockOr({ status: 'REVISION_REQUESTED' as DeliverableStatus }),

  /**
   * POST /deliverables/:id/reject  (brand) — D-9 (BrandF.md §25). The backend route
   * (BrandDeliverableController#reject → BrandDeliverableService#reject) existed with no
   * client method calling it; this was the missing half.
   */
  reject: (id: string, feedback: string) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/reject`, {
          body: { feedback },
        })
      : mockOr({ status: 'REJECTED' as DeliverableStatus }),

  /**
   * GET /deliverables/:id/safety-review — Brand Surface Audit fix #3
   * (wiki/reports/brand-feature-audit.md item 3: "No code path scores
   * submitted deliverable content"). Route + shape CONFIRMED against
   * Vikram's real `DeliverableSafetyDtos.java` / `BrandDeliverableController.java`
   * by Priya's contract reconciliation (wiki/build/brand-fixes-priya-review.md
   * §"#3 DeliverableSafetyReview — contract CLEAN, PASS") — exact route,
   * exact verdict/status enum casing, exact field names. Advisory only — the
   * FE never blocks approve/reject on this (see DeliverableSafetyReviewCard;
   * confirmed independently by Kabir's red-team pass, invariant 4).
   */
  getSafetyReview: (id: string): Promise<DeliverableSafetyReview> =>
    isLive()
      ? http.request<DeliverableSafetyReview>('GET', `/deliverables/${id}/safety-review`)
      : mockOr<DeliverableSafetyReview>({
          overallVerdict: 'PASS',
          // Mock ids/labels mirror the real, fixed 10-category GARM set
          // (app/tools/schemas.py::GARM_CATEGORIES, labeled in
          // DeliverableSafetyReviewService.buildCategoryLabels) — the earlier
          // 3-id illustrative mock (disclosure/brand_mention/garm_risk) didn't
          // match what the live service actually returns (Priya's review, nit).
          checks: [
            { id: 'adult_explicit_sexual_content', label: 'Adult / explicit sexual content', status: 'PASS' },
            { id: 'arms_ammunition', label: 'Arms & ammunition', status: 'PASS' },
            { id: 'crime_harmful_acts_to_individuals', label: 'Crime / harmful acts to individuals', status: 'PASS' },
            { id: 'death_injury_military_conflict', label: 'Death, injury & military conflict', status: 'PASS' },
            { id: 'hate_speech_acts_of_aggression', label: 'Hate speech & acts of aggression', status: 'PASS' },
            { id: 'illegal_drugs_tobacco_alcohol', label: 'Illegal drugs, tobacco & alcohol', status: 'PASS' },
            { id: 'obscenity_profanity', label: 'Obscenity & profanity', status: 'PASS' },
            { id: 'spam_or_harmful_content', label: 'Spam or harmful content', status: 'PASS' },
            { id: 'terrorism', label: 'Terrorism', status: 'PASS' },
            { id: 'debated_sensitive_social_issues', label: 'Debated sensitive social issues', status: 'PASS' },
          ],
          score: 96,
          computedAt: new Date().toISOString(),
        }),
};

// ---------------------------------------------------------------------------
// Payments / Wallet
// ---------------------------------------------------------------------------

/** GET /wallet — summary card figures. `runwayDays` is null when it can't be derived. */
export interface WalletSummaryResponse {
  availableBalance: number;
  escrowLocked: number;
  pendingPayouts: number;
  runwayDays: number | null;
}

/** GET /wallet/transactions row — MoneyDtos.WalletTransactionRowResponse. */
export interface WalletTransactionRow {
  id: string;
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'ESCROW_HOLD' | 'ESCROW_RELEASE' | 'ESCROW_REFUND' | 'PLATFORM_FEE' | 'PAYOUT' | 'ADJUSTMENT';
  direction: 'DEBIT' | 'CREDIT';
  amount: number;
  currency: string;
  description: string;
  status: string;
  createdAt: string;
  balanceAfter: number;
}

/** POST /wallet/topup response — Razorpay order to hand to Checkout (WalletDtos WalletTopUpResponse). */
export interface WalletTopUpResponse {
  topUpId: string;
  amount: number;
  currency: string;
  razorpayOrderId: string;
  status: string;
}

/**
 * GET /wallet/escrow row / GET /wallet/escrow/{id} response — MoneyDtos.EscrowStatusResponse
 * (EscrowController.java:58, :91). The list endpoint returns the exact same shape per item as the
 * single-hold status lookup.
 */
export interface EscrowHoldRow {
  escrowHoldId: string;
  status: 'PENDING' | 'FUNDED' | 'RELEASED' | 'CANCELLED';
  amount: number;
  currency: string;
  campaignId: string;
  milestoneId: string | null;
  fundedAt: string | null;
}

/**
 * GET /wallet/payout-methods row / POST /wallet/payout-methods response. Backed by the encrypted
 * CreatorBankAccountService (Kabir M-K6-C3-2) — the account number / UPI VPA is never decrypted for
 * a read, only `displayMask` (e.g. "****1234") is ever returned. `usable` reflects the 24h
 * cool-down new instruments carry before PayoutService will actually use them.
 */
export interface PayoutMethod {
  id: string;
  type: 'UPI' | 'BANK';
  displayMask: string;
  isPrimary: boolean;
  usable: boolean;
}

/**
 * Non-secret, environment-scoped config — GET /config/razorpay
 * (`PublicConfigController`). `keyId` is Razorpay's publishable "Key ID",
 * safe to embed in client-side Checkout code (it identifies the merchant,
 * it does not authorize a charge). The `keySecret` is NEVER served by any
 * endpoint the browser can reach.
 */
export interface RazorpayConfigResponse {
  keyId: string;
}

/** GET /config/public — PublicConfigController.PublicConfigResponse. */
export interface PublicConfigResponse {
  /**
   * Mirror of the server's `influora.auth.require-email-otp-before-register`. When true,
   * `AuthService.brandRegister`/`creatorRegister` reject any registration whose email has not
   * completed OTP verification, so the signup pages must run the OTP step first.
   */
  requireEmailOtp: boolean;
}

export const config = {
  /** GET /config/razorpay — source of the `key` param for `window.Razorpay(...)`. */
  razorpay: () =>
    isLive()
      ? http.request<RazorpayConfigResponse>('GET', '/config/razorpay')
      // Mock mode never talks to a real Razorpay account — this key is not live/usable.
      : mockOr<RazorpayConfigResponse>({ keyId: 'rzp_test_mock' }),

  /**
   * GET /config/public — the one unauthenticated config read (SecurityConfig permitAll), because
   * the signup pages need it before a token exists.
   *
   * Fails CLOSED to `requireEmailOtp: false`: if this call fails the user still gets a working
   * signup form, and a server that actually requires OTP will reject the registration with a
   * readable `EMAIL_NOT_VERIFIED` error. The opposite default would hard-block signup whenever
   * config is briefly unreachable.
   */
  public: async (): Promise<PublicConfigResponse> => {
    if (!isLive()) return mockOr<PublicConfigResponse>({ requireEmailOtp: false });
    try {
      return await http.request<PublicConfigResponse>('GET', '/config/public');
    } catch {
      return { requireEmailOtp: false };
    }
  },
};

export const wallet = {
  /** GET /wallet */
  get: (role: Role) =>
    isLive()
      ? http.request<WalletSummaryResponse>('GET', '/wallet', { role })
      : mockOr<WalletSummaryResponse>(
          role === 'creator'
            ? { availableBalance: 120000, escrowLocked: 180000, pendingPayouts: 45000, runwayDays: null }
            : { availableBalance: 285000, escrowLocked: 450000, pendingPayouts: 75000, runwayDays: 47 },
        ),

  /**
   * POST /wallet/topup (brand) — initiates a Razorpay order; the ledger is credited
   * asynchronously off the Razorpay webhook. `Idempotency-Key` is required by the
   * server (WalletController.java:88). The caller opens Razorpay Checkout with the
   * returned `razorpayOrderId`, then re-fetches the balance.
   */
  topUp: (body: { amount: number; pan?: string; gstin?: string }, idempotencyKey: string) =>
    isLive()
      ? http.request<WalletTopUpResponse>('POST', '/wallet/topup', { body, idempotencyKey })
      : mockOr<WalletTopUpResponse>({
          topUpId: 'tu_mock', amount: body.amount, currency: 'INR',
          razorpayOrderId: 'order_mock', status: 'PENDING',
        }),

  // `recharge` (POST /wallet/recharge) was removed 2026-07-26: no Java controller has ever
  // exposed that path — api-contract.test.ts listed it under KNOWN_PHANTOM_PATHS — and it had no
  // callers. Brand wallet funding is `topUp` above (POST /wallet/topup, WalletController.java:94).

  /** GET /creator/platform-fee (CreatorPlatformFeeController) — global fee for transparency UI. */
  platformFee: () =>
    isLive()
      ? http.request<{ feeBps: number; feePercent: number; source: string }>(
          'GET', '/creator/platform-fee', { role: 'creator' },
        )
      : mockOr<{ feeBps: number; feePercent: number; source: string }>({
          feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT',
        }),

  /**
   * GET /brand/platform-fee (BrandPlatformFeeController.java:29) — the brand-side counterpart,
   * which additionally returns `copy` (server-authored disclosure text).
   *
   * Added 2026-07-26: the endpoint had existed with no client, so the deal-room proposal form
   * hardcoded "Platform Fee (10%)" while the real default is 15% (application.yml
   * PLATFORM_FEE_PERCENT). A brand budgeting off that number under-quoted its own cost.
   */
  brandPlatformFee: () =>
    isLive()
      ? http.request<{ feeBps: number; feePercent: number; source: string; copy: string }>(
          'GET', '/brand/platform-fee',
        )
      : mockOr<{ feeBps: number; feePercent: number; source: string; copy: string }>({
          feeBps: 1500, feePercent: 15, source: 'GLOBAL_DEFAULT', copy: '',
        }),

  /**
   * POST /wallet/withdraw  (creator). `Idempotency-Key` is mandatory — WalletController's
   * `@RequestHeader` is technically optional, but `WalletService.requestCreatorWithdrawal`
   * throws `IDEMPOTENCY_KEY_REQUIRED` (400) if it's missing/blank, so the caller must always
   * pass one (B10 — money-moving mutation reachable by client retry).
   */
  withdraw: (amount: number, idempotencyKey: string) =>
    isLive()
      ? http.request<{ payoutId: string }>('POST', '/wallet/withdraw', {
          role: 'creator',
          body: { amount },
          idempotencyKey,
        })
      : mockOr({ payoutId: 'po_new' }),

  /**
   * GET /wallet/transactions — WalletController.java:135 (creator-scoped ledger, paginated).
   * `period` (CR-72) mirrors the History tab dropdown verbatim ("this-month" / "last-month" /
   * "3-months" / "all") and is resolved to a date range server-side (WalletService); omitted or
   * "all" is unfiltered, matching prior behavior.
   */
  transactions: (role: Role, page = 1, limit = 20, period?: string) =>
    isLive()
      ? http.request<WalletTransactionRow[]>('GET', '/wallet/transactions', {
          role,
          query: period && period !== 'all' ? { page, limit, period } : { page, limit },
        })
      : mockOr<WalletTransactionRow[]>([]),

  /** GET /wallet/payout-methods */
  getPayoutMethods: (role: Role) =>
    isLive()
      ? http.request<PayoutMethod[]>('GET', '/wallet/payout-methods', { role })
      : mockOr<PayoutMethod[]>([]),

  /**
   * POST /wallet/payout-methods. `accountOrVpa` (and `ifsc` for bank instruments) are write-only —
   * encrypted server-side immediately, never stored in plaintext, never returned by any endpoint
   * after this call. The first instrument a creator adds becomes primary automatically; every
   * instrument after that starts non-primary (call setPrimaryPayoutMethod to switch).
   */
  addPayoutMethod: (
    role: Role,
    payload: { type: 'UPI' | 'BANK'; accountOrVpa: string; ifsc?: string; displayMask?: string },
  ) =>
    isLive()
      ? http.request<PayoutMethod>('POST', '/wallet/payout-methods', { role, body: payload })
      : mockOr<PayoutMethod>({
          id: 'pm_new',
          type: payload.type,
          displayMask: '****' + payload.accountOrVpa.slice(-4),
          isPrimary: false,
          usable: false,
        }),

  /** PUT /wallet/payout-methods/:id/primary — returns the now-primary instrument. */
  setPrimaryPayoutMethod: (role: Role, id: string) =>
    isLive()
      ? http.request<PayoutMethod>('PUT', `/wallet/payout-methods/${id}/primary`, { role })
      : mockOr<PayoutMethod>({ id, type: 'UPI', displayMask: '****0000', isPrimary: true, usable: true }),

  /**
   * GET /wallet/escrow (brand-scoped, paginated) — EscrowController.java:58. Backs the
   * brand-wallet page's escrow-items panel. Server resolves the workspace from the auth
   * principal, so no `role`/workspace id is passed — this is brand-only (creator calls
   * getEscrowStatus per-hold via the Meera API instead).
   */
  escrowList: (page = 1, limit = 20) =>
    isLive()
      ? http.request<EscrowHoldRow[]>('GET', '/wallet/escrow', { query: { page, limit } })
      : mockOr<EscrowHoldRow[]>([]),
};

// ---------------------------------------------------------------------------
// Creator self-profile — MeCreatorProfileController (/me/creator-profile)
// ---------------------------------------------------------------------------

/** Mirrors CreatorProfileDtos.PlatformStatResponse (CreatorDtos.java). */
export interface CreatorPlatformStat {
  platform: string;
  handle: string;
  followers: number;
  engagementRate: number;
  isVerified: boolean;
  profileUrl: string | null;
}

/** GET /me/creator-profile response — CreatorProfileDtos.CreatorProfileSelfResponse. */
export interface CreatorProfileSelfResponse {
  id: string;
  userId: string;
  displayName: string;
  username: string | null;
  bio: string | null;
  avatarUrl: string | null;
  coverImageUrl: string | null;
  city: string | null;
  categories: string[];
  languages: string[];
  contentStyles: string[];
  platforms: CreatorPlatformStat[];
  rateMin: number | null;
  rateMax: number | null;
  currency: string;
  discoverable: boolean;
  verified: boolean;
  totalFollowers: number;
  engagementRate: number;
  onboardingComplete: boolean;
  profileCompleteness: number;
}

/** PATCH /me/creator-profile body — CreatorProfileDtos.CreatorProfilePatchRequest. */
export interface CreatorProfilePatchPayload {
  displayName?: string;
  username?: string;
  bio?: string;
  avatarUrl?: string;
  coverImageUrl?: string;
  city?: string;
  categories?: string[];
  languages?: string[];
  contentStyles?: string[];
  rateMin?: number;
  rateMax?: number;
  discoverable?: boolean;
}

const mockCreatorProfileSelf: CreatorProfileSelfResponse = {
  id: 'cr_mock',
  userId: 'user_mock',
  displayName: 'Priya Creates',
  username: 'priya_creates',
  bio: 'Fashion & lifestyle content creator.',
  avatarUrl: '',
  coverImageUrl: '',
  city: 'Mumbai',
  categories: ['Fashion & Lifestyle', 'Beauty & Skincare'],
  languages: ['Hindi', 'English'],
  contentStyles: [],
  platforms: [
    { platform: 'instagram', handle: '@priya_creates', followers: 125000, engagementRate: 4.2, isVerified: true, profileUrl: null },
  ],
  rateMin: 25000,
  rateMax: 75000,
  currency: 'INR',
  discoverable: true,
  verified: true,
  totalFollowers: 125000,
  engagementRate: 4.2,
  onboardingComplete: true,
  profileCompleteness: 80,
};

export const creatorProfile = {
  /** GET /me/creator-profile */
  getMe: () =>
    isLive()
      ? http.request<CreatorProfileSelfResponse>('GET', '/me/creator-profile', { role: 'creator' })
      : mockOr<CreatorProfileSelfResponse>(mockCreatorProfileSelf),

  /** PATCH /me/creator-profile */
  patchMe: (payload: CreatorProfilePatchPayload) =>
    isLive()
      ? http.request<CreatorProfileSelfResponse>('PATCH', '/me/creator-profile', {
          role: 'creator',
          body: payload,
        })
      : mockOr<CreatorProfileSelfResponse>({ ...mockCreatorProfileSelf, ...payload } as CreatorProfileSelfResponse),
};

// Account self-service — MeAccountController (/me/account). Soft-delete only: server
// anonymizes PII and marks the account closed/unable-to-login; it does not hard-delete
// records that must be retained for legal/compliance reasons (e.g. completed deals).
export const me = {
  /** DELETE /me/account */
  deleteAccount: (role: Role) =>
    isLive()
      ? http.request<{ success: boolean }>('DELETE', '/me/account', { role })
      : mockOr<{ success: boolean }>({ success: true }),
};

export const payments = {
  /**
   * POST /wallet/escrow/fund — brand funds escrow before delivery (EscrowController.fund).
   * [SEC: MF-1 / Guardrail 1] No amount is sent — the server derives it from the campaign's
   * persisted budget or the named milestone. `Idempotency-Key` is required by the controller.
   */
  fundEscrow: (campaignId: string, idempotencyKey: string, milestoneId?: string) =>
    isLive()
      ? http.request<{
          escrowHoldId: string;
          amount: number;
          currency: string;
          razorpayOrderId: string;
          status: string;
        }>('POST', '/wallet/escrow/fund', {
          body: { campaignId, milestoneId: milestoneId ?? null },
          idempotencyKey,
        })
      : mockOr({
          escrowHoldId: 'escrow_new',
          amount: 17250,
          currency: 'INR',
          razorpayOrderId: 'order_mock',
          status: 'PENDING',
        }),

  /** POST /wallet/escrow/release — brand releases a funded milestone's escrow to the creator. */
  releasePayout: (milestoneId: string) =>
    isLive()
      ? http.request<{ escrowHoldId: string; status: string }>('POST', '/wallet/escrow/release', {
          body: { milestoneId },
        })
      : mockOr({ escrowHoldId: 'escrow_new', status: 'RELEASED' }),
};

// ---------------------------------------------------------------------------
// Dashboard / activity
// ---------------------------------------------------------------------------

export const dashboard = {
  /** GET /dashboard/actions  — priority items needing attention */
  actions: (role: Role) =>
    isLive()
      ? http.request<Array<{
          id: string;
          type: 'deliverable_review' | 'counter_proposal' | 'payment_release' | 'sign_contract';
          title: string;
          subtitle: string;
          deadline: string;
          priority: 'urgent' | 'high' | 'medium';
          amount: number;
          link: string;
        }>>('GET', '/dashboard/actions', { role })
      : mockOr([]),

  /** GET /dashboard/pipeline */
  pipeline: (role: Role) =>
    isLive()
      ? http.request<Array<{ stage: string; count: number }>>('GET', '/dashboard/pipeline', { role })
      : mockOr([]),
};

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

export interface NotificationItem {
  id: string;
  type: 'info' | 'success' | 'warning' | 'meera_nudge';
  title: string;
  body?: string;
  read: boolean;
  createdAt: string;
  link?: string;
  surfaceInChat?: boolean;
}

/** Single row returned by GET /notifications/preferences (Domain B email-preference model). */
export interface NotificationPreference {
  eventType: string;
  unsubscribed: boolean;
}

/**
 * Raw wire shape of NotificationController's GET /notifications
 * (NotificationDtos.NotificationListResponse / NotificationResponse) — `eventType`/`isRead`,
 * not the FE-classified `type`/`read` that NotificationItem exposes.
 */
interface NotificationListWire {
  notifications: Array<{
    id: string;
    eventType: string;
    title: string;
    body: string | null;
    link: string | null;
    isRead: boolean;
    createdAt: string;
  }>;
  unreadCount: number;
  page: number;
  size: number;
}

function classifyNotificationEventType(eventType: string): NotificationItem['type'] {
  if (eventType.startsWith('ai.')) return 'meera_nudge';
  if (/failed|rejected|halted|low_balance|exhausted|disputed/i.test(eventType)) return 'warning';
  if (/funded|signed|accepted|released|approved|received|reset|completed/i.test(eventType)) return 'success';
  return 'info';
}

function fromNotificationWire(n: NotificationListWire['notifications'][number]): NotificationItem {
  return {
    id: n.id,
    type: classifyNotificationEventType(n.eventType),
    title: n.title,
    body: n.body ?? undefined,
    read: n.isRead,
    createdAt: n.createdAt,
    link: n.link ?? undefined,
  };
}

export const notifications = {
  /**
   * GET /notifications. N-1 fix (BrandF.md §74): this — not a raw `fetch` in
   * `useNotifications.ts` — is now the only place that calls the endpoint, so a 401 gets
   * `fetchWithAuthRetry`'s refresh-and-retry instead of failing outright. Was previously
   * mistyped as returning `NotificationItem[]` directly; the endpoint actually returns the
   * `{notifications, unreadCount, page, size}` envelope payload — fixed here and mapped
   * through fromNotificationWire.
   */
  list: async (role: Role): Promise<{ items: NotificationItem[]; unreadCount: number }> => {
    if (!isLive()) return mockOr({ items: [] as NotificationItem[], unreadCount: 0 });
    const wire = await http.request<NotificationListWire>('GET', '/notifications', { role });
    return { items: (wire.notifications ?? []).map(fromNotificationWire), unreadCount: wire.unreadCount ?? 0 };
  },

  /** POST /notifications/read — body: { notificationId } (NotificationController.java). */
  markRead: (role: Role, id: string) =>
    isLive()
      ? http.request<{ success: boolean; newUnreadCount: number }>('POST', '/notifications/read', { role, body: { notificationId: id } })
      : mockOr({ success: true as const, newUnreadCount: 0 }),

  /**
   * POST /notifications/read-all — bulk mark-all-read (NotificationController#markAllRead).
   * N-1/N-2: previously had no client method at all; useNotifications.ts called the raw
   * endpoint with `fetch` directly.
   */
  markAllRead: (role: Role) =>
    isLive()
      ? http.request<{ success: boolean; newUnreadCount: number }>('POST', '/notifications/read-all', { role })
      : mockOr({ success: true as const, newUnreadCount: 0 }),

  /**
   * GET /notifications/preferences — per-event-type email unsubscribe state for the
   * authenticated user (Domain B, 07-NOTIFICATION-SYSTEM-SPEC.md EmailPreference model).
   * Real route as of 2026-07-18 (NotificationController.getPreferences), backed by the existing
   * email_preferences table — auth-scoped server-side via AuthPrincipal, never a request param.
   * Sparse list: an event type absent from the response is implicitly subscribed. Use eventType
   * "*" for the global email opt-out. There is no backend model for push/SMS/digest channels —
   * this only covers email.
   */
  getPreferences: (role: Role) =>
    isLive()
      ? http
          .request<{ preferences: NotificationPreference[] }>('GET', '/notifications/preferences', { role })
          .then((r) => r.preferences)
      : mockOr<NotificationPreference[]>([]),

  /**
   * POST /notifications/preferences — subscribe/unsubscribe a single event type's email.
   * Real route as of 2026-07-18 (NotificationController.setPreference).
   */
  setPreference: (role: Role, eventType: string, subscribed: boolean) =>
    isLive()
      ? http.request<void>('POST', '/notifications/preferences', {
          role,
          body: { eventType, subscribed },
        })
      : mockOr(undefined as void),
};

// ---------------------------------------------------------------------------
// Billing / subscription (Task 26 — mirrors BillingController + BillingDtos)
// ---------------------------------------------------------------------------

export interface BillingPlan {
  code: 'FREE' | 'PRO';
  name: string;
  priceInr: number;
  billingCycle: string;
  feeBps: number;
  aiMonthlyAllotment: number;
  seatLimit: number;
  trackedCreatorLimit: number | null;
  creatorAnalyticsMonthlyLimit: number | null;
  exportEnabled: boolean;
  campaignTemplatesEnabled: boolean;
}

export interface BillingSubscription {
  status: 'ACTIVE' | 'PAST_DUE' | 'HALTED' | 'CANCELLED';
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  cancelAtPeriodEnd: boolean;
}

export interface BillingPlanStatus {
  plan: BillingPlan;
  subscription: BillingSubscription;
}

export interface BillingInvoice {
  id: string;
  amount: number;
  status: 'PAID' | 'PENDING' | 'FAILED' | string;
  periodStart: string;
  periodEnd: string;
  issuedAt: string | null;
  paidAt: string | null;
  pdfDownloadUrl: string;
}

export interface BillingUsageSummary {
  periodStart: string;
  trackedCreatorsUsed: number;
  trackedCreatorLimit: number | null;
  analyticsViewsUsed: number;
  analyticsViewsLimit: number | null;
  exportsUsed: number;
  exportEnabled: boolean;
  aiCreditsRemaining: number;
  aiCreditsMonthlyAllotment: number;
  activeSeatsUsed: number;
}

const mockBillingPlanStatus: BillingPlanStatus = {
  plan: {
    code: 'FREE',
    name: 'Free',
    priceInr: 0,
    billingCycle: 'MONTHLY',
    feeBps: 1000,
    aiMonthlyAllotment: 150,
    seatLimit: 1,
    trackedCreatorLimit: 5,
    creatorAnalyticsMonthlyLimit: 1,
    exportEnabled: false,
    campaignTemplatesEnabled: false,
  },
  subscription: {
    status: 'ACTIVE',
    currentPeriodStart: null,
    currentPeriodEnd: null,
    cancelAtPeriodEnd: false,
  },
};

const mockBillingUsage: BillingUsageSummary = {
  periodStart: new Date().toISOString().slice(0, 10),
  trackedCreatorsUsed: 3,
  trackedCreatorLimit: 5,
  analyticsViewsUsed: 1,
  analyticsViewsLimit: 1,
  exportsUsed: 0,
  exportEnabled: false,
  aiCreditsRemaining: 63,
  aiCreditsMonthlyAllotment: 150,
  activeSeatsUsed: 1,
};

export const billing = {
  /**
   * GET /billing/plan — active plan + subscription state for the caller's workspace.
   * Priya's note (SUBSCRIPTION-BILLING-PLAN.md §0.5): never cache this beyond the
   * component lifetime — `aiMonthlyAllotment` only reconciles on plan-change webhooks
   * server-side, so a stale client cache can show numbers that no longer match a brand
   * that just up/downgraded. Callers should use a short/zero `staleTime`.
   */
  getPlan: () =>
    isLive()
      ? http.request<BillingPlanStatus>('GET', '/billing/plan')
      : mockOr(mockBillingPlanStatus),

  /** GET /billing/invoices — most recent first. */
  getInvoices: () =>
    isLive()
      ? http.request<BillingInvoice[]>('GET', '/billing/invoices')
      : mockOr<BillingInvoice[]>([]),

  /** GET /billing/usage — current-cycle usage meters. */
  getUsage: () =>
    isLive()
      ? http.request<BillingUsageSummary>('GET', '/billing/usage')
      : mockOr(mockBillingUsage),

  /** GET /billing/invoices/:id/pdf — returns the raw PDF bytes, not the JSON envelope. */
  downloadInvoicePdf: (invoiceId: string) =>
    isLive()
      ? http.downloadBlob(`/billing/invoices/${encodeURIComponent(invoiceId)}/pdf`)
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Invoice PDFs are not available in mock mode')),

  /**
   * POST /billing/checkout — real Razorpay Subscriptions checkout. SubscriptionService
   * lazily backfills the Pro plan's razorpay_plan_id on first call and returns a hosted
   * checkoutUrl. Live behaviour is gated on provisioned Razorpay keys (placeholder keys
   * surface as an API error, same as escrow-fund) — not a stub.
   */
  initiateCheckout: (planCode: 'FREE' | 'PRO') =>
    isLive()
      ? http.request<{ checkoutUrl: string }>('POST', '/billing/checkout', { body: { planCode } })
      : Promise.reject(new ApiError('NOT_YET_IMPLEMENTED', 'Razorpay checkout ships in Phase 2')),

  /**
   * POST /billing/cancel — real cancellation. SubscriptionService calls Razorpay
   * cancelSubscription and persists cancelAtPeriodEnd=true; access continues until the
   * period ends. Not a stub.
   */
  cancelSubscription: () =>
    isLive()
      ? http.request<void>('POST', '/billing/cancel')
      : Promise.reject(new ApiError('NOT_YET_IMPLEMENTED', 'Cancellation ships in Phase 2')),
};

// ---------------------------------------------------------------------------
// BR-37 report export — ReportExportController @ /campaigns/:id/export.
// Pro-gated (@RequiresPlan EXPORT) — a non-Pro workspace gets a 402, which the caller
// surfaces as an upgrade prompt rather than letting the download silently fail.
// ---------------------------------------------------------------------------

export type ReportExportFormat = 'csv' | 'pdf';

export const reports = {
  /** GET /campaigns/:campaignId/export?format=csv|pdf — raw file bytes, not the JSON envelope. */
  exportCampaign: (campaignId: string, format: ReportExportFormat) =>
    isLive()
      ? http.downloadBlob(
          `/campaigns/${encodeURIComponent(campaignId)}/export?format=${format}`,
        )
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Report export is not available in mock mode')),
};

// ---------------------------------------------------------------------------
// D14 marketplace invoicing — Doc#2 (campaign_service_invoices, Creator -> Brand)
// and Doc#3 (platform_commission_invoices, split BRAND/CREATOR legs).
// Backed by CreatorInvoicingController (/creator/*) and BrandInvoicingController
// (/billing/*) — see InvoicingDtos.java. Read-only; PDFs are ownership-checked
// server-side the same way BillingController#getInvoicePdf is.
// ---------------------------------------------------------------------------

export type MarketplaceInvoiceStatus = 'ISSUED' | 'PAID';
export type CommissionInvoiceLeg = 'BRAND' | 'CREATOR';

/** Doc#2 — the creator's service invoice to the brand for a collaboration. */
export interface CampaignServiceInvoice {
  id: string;
  invoiceNumber: string;
  collaborationId: string;
  campaignId: string;
  creatorUserId: string;
  brandWorkspaceId: string;
  grossAmount: number;
  currency: string;
  creatorGstin?: string;
  tcsAmount?: number;
  hsnSacCode?: string;
  status: MarketplaceInvoiceStatus;
  issuedAt: string;
  pdfUrl: string;
}

/** Doc#3 — Influora's commission invoice, split into a BRAND leg and a CREATOR leg. */
export interface PlatformCommissionInvoice {
  id: string;
  invoiceNumber: string;
  leg: CommissionInvoiceLeg;
  campaignId: string;
  counterpartyWorkspaceId: string;
  counterpartyUserId: string;
  feeBpsApplied: number;
  commissionAmount: number;
  gstAmount?: number;
  hsnSacCode?: string;
  status: MarketplaceInvoiceStatus;
  issuedAt: string;
  pdfUrl: string;
}

/** Brand-facing reads, mounted at /billing alongside the subscription-invoice endpoints. */
export const brandInvoicing = {
  /** GET /billing/campaign-invoices — Doc#2 invoices billed to this workspace, most recent first. */
  getCampaignInvoices: () =>
    isLive()
      ? http.request<CampaignServiceInvoice[]>('GET', '/billing/campaign-invoices')
      : mockOr<CampaignServiceInvoice[]>([]),

  /** GET /billing/campaign-invoices/:id/pdf */
  downloadCampaignInvoicePdf: (invoiceId: string) =>
    isLive()
      ? http.downloadBlob(`/billing/campaign-invoices/${encodeURIComponent(invoiceId)}/pdf`)
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Invoice PDFs are not available in mock mode')),

  /** GET /billing/commission-invoices — Doc#3a (leg BRAND), most recent first. */
  getCommissionInvoices: () =>
    isLive()
      ? http.request<PlatformCommissionInvoice[]>('GET', '/billing/commission-invoices')
      : mockOr<PlatformCommissionInvoice[]>([]),

  /** GET /billing/commission-invoices/:id/pdf */
  downloadCommissionInvoicePdf: (invoiceId: string) =>
    isLive()
      ? http.downloadBlob(`/billing/commission-invoices/${encodeURIComponent(invoiceId)}/pdf`)
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Invoice PDFs are not available in mock mode')),
};

/** Creator-facing reads, mounted at /creator (CreatorInvoicingController). */
export const creatorInvoicing = {
  /** GET /creator/campaign-invoices — the creator's own Doc#2 earnings invoices, most recent first. */
  getCampaignInvoices: () =>
    isLive()
      ? http.request<CampaignServiceInvoice[]>('GET', '/creator/campaign-invoices', { role: 'creator' })
      : mockOr<CampaignServiceInvoice[]>([]),

  /** GET /creator/campaign-invoices/:id/pdf */
  downloadCampaignInvoicePdf: (invoiceId: string) =>
    isLive()
      ? http.downloadBlob(`/creator/campaign-invoices/${encodeURIComponent(invoiceId)}/pdf`, 'creator')
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Invoice PDFs are not available in mock mode')),

  /** GET /creator/commission-invoices — Doc#3b (leg CREATOR, Influora's commission invoice to the creator). */
  getCommissionInvoices: () =>
    isLive()
      ? http.request<PlatformCommissionInvoice[]>('GET', '/creator/commission-invoices', { role: 'creator' })
      : mockOr<PlatformCommissionInvoice[]>([]),

  /** GET /creator/commission-invoices/:id/pdf */
  downloadCommissionInvoicePdf: (invoiceId: string) =>
    isLive()
      ? http.downloadBlob(`/creator/commission-invoices/${encodeURIComponent(invoiceId)}/pdf`, 'creator')
      : Promise.reject(new ApiError('NOT_AVAILABLE', 'Invoice PDFs are not available in mock mode')),
};

/**
 * Creator tax-identity (GSTIN/PAN) capture — D14-B schema-captures this on
 * `CreatorProfile.gstin`/`.pan`/`.taxRegistrationStatus` (see `CreatorProfile#applyTaxIdentity`).
 * Vikram shipped the real endpoint 2026-07-24 — `POST /me/tax-identity` (creator-scoped),
 * validates GSTIN/PAN format server-side and 400s with `INVALID_GSTIN` / `INVALID_PAN` /
 * `TAX_IDENTITY_REQUIRED`. Success returns the persisted, masked record.
 */
export interface CreatorTaxIdentitySubmission {
  gstin?: string;
  pan?: string;
}

/** Response shape of `POST /me/tax-identity` — PAN is always masked, never returned in full. */
export interface CreatorTaxIdentityResponse {
  gstin: string | null;
  maskedPan: string | null;
  taxRegistrationStatus: string | null;
}

const maskPan = (pan: string): string => pan.replace(/.(?=.{4})/g, '*');

export const creatorTaxIdentity = {
  submit: (body: CreatorTaxIdentitySubmission) =>
    isLive()
      ? http.request<CreatorTaxIdentityResponse>('POST', '/me/tax-identity', { role: 'creator', body })
      : mockOr<CreatorTaxIdentityResponse>({
          gstin: body.gstin ?? null,
          maskedPan: body.pan ? maskPan(body.pan) : null,
          taxRegistrationStatus: body.gstin ? 'GST_REGISTERED' : body.pan ? 'PAN_ONLY' : null,
        }),
};

// ---------------------------------------------------------------------------
// File uploads (logo, KYC docs, deliverable files)
// ---------------------------------------------------------------------------

export const uploads = {
  /** POST /uploads  → returns { url, key } stored in S3/R2 */
  upload: (file: File, role: Role = 'brand') =>
    isLive()
      ? http.upload<{ url: string; key: string }>('/uploads', file, role)
      : mockOr({ url: URL.createObjectURL(file), key: `mock_${file.name}` }),
};

// ---------------------------------------------------------------------------
// Public Portfolio Page  (influora.com/@username)
// See docs/CREATOR-PORTFOLIO-PAGE.md
// ---------------------------------------------------------------------------

export type PortfolioBadge =
  | 'top_creator'
  | 'fast_responder'
  | 'on_time'
  | 'brand_favorite'
  | 'rising_star'
  | 'premium';

export interface PortfolioPlatformStats {
  platform: Platform;
  handle: string;
  url: string;
  verified: boolean;
  followers: number;
  engagementRate: number;
  avgReach?: number;
  /**
   * ISO timestamp of the last platform sync. CR-14 — declared non-nullable
   * here, but the live `GET /portfolio/:username` response omits it for a
   * platform that has never completed a sync, which is how the public page
   * came to render the literal "Synced NaNd ago". Typed to match what the
   * server actually sends so the consumer's guard isn't dead code.
   */
  lastSyncedAt?: string | null;
}

export interface PortfolioCollab {
  id: string;
  brandId: string;
  brandName: string;
  brandLogoUrl?: string;
  campaignTitle: string;
  deliverables: string;          // e.g. "2 Reels + 4 Stories"
  platform: Platform;
  completedAt: string;            // ISO
  rating?: number;                // 1–5
  publicQuote?: string;
  displayMode: 'logo' | 'name_only' | 'category' | 'hidden';
}

export interface PortfolioPinnedPost {
  id: string;
  platform: Platform;
  embedUrl: string;
  thumbnailUrl?: string;
  caption?: string;
  views?: number;
  likes?: number;
}

export interface PortfolioCustomLink {
  id: string;
  label: string;
  url: string;
  icon?: string; // lucide icon name, optional
  clicks?: number;
}

export interface PortfolioRateRow {
  id: string;
  label: string;             // "Instagram Reel"
  min: number;
  max: number;
  currency: 'INR' | 'USD';
}

export interface PortfolioVisibility {
  trustBar: boolean;
  badges: boolean;
  platformStats: boolean;
  pastCollabs: boolean;
  contentPortfolio: boolean;
  customLinks: boolean;
  rateCard: 'public' | 'brands_only' | 'hidden';
  languages: boolean;
  contactForm: boolean;
}

export interface PortfolioPage {
  username: string;
  displayName: string;
  bio: string;
  city?: string;
  niches: string[];               // up to 3 — "Fashion & Lifestyle", etc.
  avatarUrl?: string;
  coverUrl?: string;
  verified: boolean;

  // Trust signals (computed server-side from deal history)
  stats: {
    totalCollabs: number;
    avgRating: number;            // 0–5
    onTimeRate: number;           // 0–100 (%)
    repeatBrands: number;
  };

  badges: PortfolioBadge[];
  platforms: PortfolioPlatformStats[];
  collabs: PortfolioCollab[];
  pinnedPosts: PortfolioPinnedPost[];
  customLinks: PortfolioCustomLink[];
  rateCard: PortfolioRateRow[];
  languages: string[];
  topAudienceCities: string[];

  visibility: PortfolioVisibility;
}

export interface PortfolioAnalytics {
  pageViews: { last30Days: number; deltaPercent: number };
  profileClicks: number;
  /** CR-71 — profileClicks is a follower-count proxy, not a real click measurement. Always
   *  true today (no real click-tracking event exists yet); read from the server rather than
   *  hardcoded so the label stops being true the moment real tracking ships. */
  profileClicksEstimated: boolean;
  linkClicks: Array<{ linkId: string; label: string; clicks: number }>;
  brandInquiries: number;
  mediaKitDownloads: number;
}

export const portfolio = {
  /**
   * GET /portfolio/:username  (PUBLIC — no auth required)
   * Returns the assembled portfolio page for a creator handle.
   */
  getPublic: (username: string) =>
    isLive()
      ? http.request<PortfolioPage>('GET', `/portfolio/${encodeURIComponent(username)}`)
      : mockOr<PortfolioPage>(mockPortfolio(username)),

  /** GET /me/portfolio  (creator-only) */
  getMine: () =>
    isLive()
      ? http.request<PortfolioPage>('GET', '/me/portfolio', { role: 'creator' })
      : mockOr<PortfolioPage>(mockPortfolio('priyacreates')),

  /** PATCH /me/portfolio  — update bio, niches, visibility, custom links, rates, etc. */
  update: (patch: Partial<PortfolioPage>) =>
    isLive()
      ? http.request<PortfolioPage>('PATCH', '/me/portfolio', {
          role: 'creator',
          body: patch,
        })
      : mockOr<PortfolioPage>({ ...mockPortfolio('priyacreates'), ...patch } as PortfolioPage),

  /** POST /me/portfolio/sync  — manual platform stat refresh (rate-limited 1/hr) */
  syncPlatforms: () =>
    isLive()
      ? http.request<{ syncedAt: string }>('POST', '/me/portfolio/sync', { role: 'creator' })
      : mockOr({ syncedAt: new Date().toISOString() }),

  /** POST /me/portfolio/cover  — upload cover photo, returns CDN url */
  uploadCover: (file: File) =>
    isLive()
      ? http.upload<{ url: string }>('/me/portfolio/cover', file, 'creator')
      : mockOr({ url: URL.createObjectURL(file) }),

  /**
   * POST /portfolio/:username/contact   (PUBLIC — anti-spam protected on server)
   * Body: { name, email, message, captchaToken }
   */
  contact: (username: string, payload: { name: string; email: string; message: string; captchaToken?: string }) =>
    isLive()
      ? http.request<{ delivered: boolean }>('POST', `/portfolio/${encodeURIComponent(username)}/contact`, {
          body: payload,
        })
      : mockOr({ delivered: true }),

  // NOTE (2026-07-17): `mediaKitUrl` was removed. It pointed at
  // `GET /portfolio/{username}/media-kit.pdf`, which PortfolioController does
  // NOT expose (only getPublic/contact/getMine/updateMine/sync/cover/analytics
  // exist). Every "Media Kit (PDF)" button built on it 404'd in live mode.
  // Re-add this ONLY once the backend actually serves the PDF.

  /** GET /me/portfolio/analytics  — last 30 days */
  analytics: () =>
    isLive()
      ? http.request<PortfolioAnalytics>('GET', '/me/portfolio/analytics', { role: 'creator' })
      : mockOr<PortfolioAnalytics>({
          pageViews: { last30Days: 1247, deltaPercent: 18 },
          profileClicks: 342,
          profileClicksEstimated: true,
          linkClicks: [
            { linkId: 'l_1', label: 'Amazon Wishlist', clicks: 34 },
            { linkId: 'l_2', label: 'Photography Course', clicks: 22 },
          ],
          brandInquiries: 6,
          mediaKitDownloads: 23,
        }),
};

/* Mock portfolio used until VITE_API_MODE=live */
function mockPortfolio(username: string): PortfolioPage {
  return {
    username,
    displayName: 'Priya Creates',
    bio: 'Fashion creator helping brands tell stories through aesthetic content.',
    city: 'Mumbai',
    niches: ['Fashion & Lifestyle', 'Beauty', 'Travel'],
    avatarUrl: '',
    coverUrl: '',
    verified: true,
    stats: { totalCollabs: 45, avgRating: 4.8, onTimeRate: 95, repeatBrands: 12 },
    badges: ['top_creator', 'fast_responder', 'on_time', 'brand_favorite'],
    platforms: [
      {
        platform: 'INSTAGRAM',
        handle: '@priya_creates',
        url: 'https://instagram.com/priya_creates',
        verified: true,
        followers: 125000,
        engagementRate: 4.2,
        avgReach: 180000,
        lastSyncedAt: new Date(Date.now() - 14 * 60 * 60 * 1000).toISOString(),
      },
      {
        platform: 'YOUTUBE',
        handle: 'Priya Creates',
        url: 'https://youtube.com/@priyacreates',
        // CR-119 — was `true`. `verified` means "this follower count came back from the
        // platform's own API"; there is no YouTube OAuth or data-fetch integration anywhere in
        // this codebase, so no YouTube figure can carry that claim. This is the SECOND mock
        // fixture with the bug (the first was mockCreator in brand-creator-profile.tsx) and it
        // feeds creator-portfolio-public.tsx — a publicly linkable, brand-viewable page — in
        // every non-live build, where it printed a literal "Followers verified" next to a
        // YouTube count once the provenance wording landed.
        verified: false,
        followers: 50000,
        engagementRate: 3.8,
        avgReach: 25000,
        lastSyncedAt: new Date(Date.now() - 18 * 60 * 60 * 1000).toISOString(),
      },
    ],
    collabs: [
      { id: 'c1', brandId: 'b1', brandName: 'Nykaa Fashion',  campaignTitle: 'Summer Collection', deliverables: '2 Reels + 4 Stories', platform: 'INSTAGRAM', completedAt: '2026-06-10', rating: 5, publicQuote: 'Exceptional work — delivered ahead of schedule.', displayMode: 'logo' },
      { id: 'c2', brandId: 'b2', brandName: 'Mamaearth',      campaignTitle: 'Skincare Series',   deliverables: '3 Reels',           platform: 'INSTAGRAM', completedAt: '2026-04-20', rating: 5, displayMode: 'logo' },
      { id: 'c3', brandId: 'b3', brandName: 'BoAt',           campaignTitle: 'Earbuds Launch',    deliverables: '1 Video + 2 Posts', platform: 'YOUTUBE',   completedAt: '2026-03-15', rating: 4, displayMode: 'logo' },
      { id: 'c4', brandId: 'b4', brandName: 'Myntra',         campaignTitle: 'Festive Edit',      deliverables: '4 Reels',           platform: 'INSTAGRAM', completedAt: '2026-02-28', rating: 5, displayMode: 'logo' },
      { id: 'c5', brandId: 'b5', brandName: 'Bewakoof',       campaignTitle: 'Casual Drop',       deliverables: '2 Reels',           platform: 'INSTAGRAM', completedAt: '2026-01-10', rating: 5, displayMode: 'logo' },
      { id: 'c6', brandId: 'b6', brandName: 'Sugar Cosmetics', campaignTitle: 'Lipstick Try-On', deliverables: '1 Reel',            platform: 'INSTAGRAM', completedAt: '2025-12-05', rating: 4, displayMode: 'logo' },
    ],
    pinnedPosts: [
      { id: 'p1', platform: 'INSTAGRAM', embedUrl: 'https://instagram.com/reel/abc1',  caption: 'Summer styling tips', views: 180000 },
      { id: 'p2', platform: 'INSTAGRAM', embedUrl: 'https://instagram.com/reel/abc2',  caption: 'GRWM festival night', views: 220000 },
      { id: 'p3', platform: 'YOUTUBE',   embedUrl: 'https://youtube.com/watch?v=xyz1', caption: 'Skincare routine deep-dive', views: 85000 },
      { id: 'p4', platform: 'INSTAGRAM', embedUrl: 'https://instagram.com/p/abc3',     caption: 'Outfit grid · monsoon', likes: 12000 },
    ],
    customLinks: [
      { id: 'l1', label: 'My Amazon Wishlist',      url: 'https://amazon.in/...',      icon: 'ShoppingCart' },
      { id: 'l2', label: 'My Photography Course',   url: 'https://teachable.com/...',  icon: 'GraduationCap' },
      { id: 'l3', label: 'Book Me for Events',      url: 'https://calendly.com/...',   icon: 'Mail' },
      { id: 'l4', label: 'My LinkedIn',             url: 'https://linkedin.com/...',   icon: 'Briefcase' },
    ],
    rateCard: [
      { id: 'r1', label: 'Instagram Reel',         min: 25000, max: 45000, currency: 'INR' },
      { id: 'r2', label: 'Instagram Post',         min: 15000, max: 30000, currency: 'INR' },
      { id: 'r3', label: 'YouTube Integration',    min: 40000, max: 75000, currency: 'INR' },
      { id: 'r4', label: 'Story Series (4)',       min: 12000, max: 20000, currency: 'INR' },
    ],
    languages: ['Hindi', 'English', 'Marathi'],
    topAudienceCities: ['Mumbai', 'Delhi', 'Pune', 'Bangalore'],
    visibility: {
      trustBar: true,
      badges: true,
      platformStats: true,
      pastCollabs: true,
      contentPortfolio: true,
      customLinks: true,
      rateCard: 'hidden',
      languages: true,
      contactForm: true,
    },
  };
}

// ===========================================================================
// Reconstructed client layers (2026-07 contract-reconciliation pass)
// Every path below is verified against a real Java controller; genuinely
// missing endpoints throw a typed NOT_IMPLEMENTED rather than a fabricated path.
// ===========================================================================

// ---------------------------------------------------------------------------
// Analytics — brand-facing reads (AnalyticsController @ /analytics/creators)
// ---------------------------------------------------------------------------

/**
 * CR-70 — mock/demo mode is used to evaluate the product, and all four analytics mocks
 * previously returned zero/null everywhere (via now-deleted `emptyMetrics`/`emptyScores`/
 * `emptyDemographics` constants, which had exactly one caller each — the mock-mode branch below
 * — never a live-mode fallback, so nothing depended on them meaning "zero"), which made every
 * analytics panel look broken or empty in a demo. These replace them with illustrative
 * mock-mode-only values, following the same pattern already used elsewhere in this file for demo
 * data (e.g. `mockCreatorProfileSelf`, portfolio mocks) — clearly synthetic round numbers, never
 * claimed as real by any UI copy. Live mode is untouched; a creator who genuinely has no data yet
 * still gets exactly what the server returns, never a fabricated number.
 */
const mockMetrics: CreatorMetrics = {
  totalReach: 284000,
  totalImpressions: 412000,
  totalEngagements: 18650,
  engagementRate: 4.5,
  followerGrowth: 1240,
  avgViewsPerPost: 22400,
  trendData: Array.from({ length: 7 }, (_, i) => {
    const date = new Date(Date.now() - (6 - i) * 24 * 60 * 60 * 1000);
    return {
      date: date.toISOString().slice(0, 10),
      followers: 18400 + i * 40,
      impressions: 55000 + i * 3200,
      reach: 38000 + i * 2100,
      engagementRate: 4.1 + i * 0.08,
    };
  }),
};
const mockScores: CreatorScores = {
  authenticityScore: 92, fakeFollowerReasons: [], qualityScore: 87,
  engagementConsistency: 81, postingFrequency: 76, audienceMatchScore: 84,
  brandSafetyScore: 95, garmFlags: [], contentSentiment: 78,
  estimatedRateMin: 15000, estimatedRateMax: 30000, rateCurrency: 'INR',
  rateConfidence: 0.7, algorithmVersion: 'demo', computedAt: new Date().toISOString(),
};
const mockDemographics: CreatorDemographics = {
  hasData: true,
  ageGenderBreakdown: { '18-24_female': 34, '18-24_male': 12, '25-34_female': 28, '25-34_male': 16, '35+_other': 10 },
  countryBreakdown: { India: 82, 'United States': 8, UAE: 4, Other: 6 },
  cityBreakdown: { Mumbai: 22, Delhi: 18, Bangalore: 14, Pune: 9, Other: 37 },
  localeBreakdown: { 'en-IN': 61, 'hi-IN': 29, Other: 10 },
  fetchedAt: new Date().toISOString(),
};

export const analytics = {
  /** GET /analytics/creators/:creatorId/metrics?startDate=&endDate= (AnalyticsController.java:58) */
  getCreatorMetrics: (creatorId: string, startDate?: string, endDate?: string) =>
    isLive()
      ? http.request<CreatorMetrics>('GET', `/analytics/creators/${creatorId}/metrics`, {
          query: { startDate, endDate },
        })
      : mockOr<CreatorMetrics>(mockMetrics),

  /** GET /analytics/creators/:creatorId/scores (AnalyticsController.java:71) */
  getCreatorScores: (creatorId: string) =>
    isLive()
      ? http.request<CreatorScores>('GET', `/analytics/creators/${creatorId}/scores`)
      : mockOr<CreatorScores>(mockScores),

  /** GET /analytics/creators/:creatorId/demographics (AnalyticsController.java). */
  getCreatorDemographics: (creatorId: string): Promise<CreatorDemographics> =>
    isLive()
      ? http.request<CreatorDemographics>('GET', `/analytics/creators/${creatorId}/demographics`)
      : mockOr<CreatorDemographics>(mockDemographics),
};

// ---------------------------------------------------------------------------
// Creator self analytics (CreatorAnalyticsController @ /creator/analytics/me)
// ---------------------------------------------------------------------------

export const creatorAnalytics = {
  /** GET /creator/analytics/me/metrics?startDate=&endDate= (CreatorAnalyticsController.java:35) */
  getMyMetrics: (startDate?: string, endDate?: string) =>
    isLive()
      ? http.request<CreatorMetrics>('GET', '/creator/analytics/me/metrics', {
          role: 'creator', query: { startDate, endDate },
        })
      : mockOr<CreatorMetrics>(mockMetrics),

  /** GET /creator/analytics/me/scores (CreatorAnalyticsController.java:46) */
  getMyScores: () =>
    isLive()
      ? http.request<CreatorScores>('GET', '/creator/analytics/me/scores', { role: 'creator' })
      : mockOr<CreatorScores>(mockScores),

  /** GET /creator/analytics/me/demographics (CreatorAnalyticsController.java:52) */
  getMyDemographics: () =>
    isLive()
      ? http.request<CreatorDemographics>('GET', '/creator/analytics/me/demographics', { role: 'creator' })
      : mockOr<CreatorDemographics>(mockDemographics),

  /**
   * GET /creator/analytics/me/media — the authenticated creator's own per-post content
   * performance (CreatorAnalyticsController.java:65). Principal-scoped self-service mirror of the
   * brand-facing `/analytics/creators/:id/media`; renders in ContentPerformancePanel.
   */
  getMyMedia: (): Promise<ContentPerformanceItem[]> =>
    isLive()
      ? http.request<ContentPerformanceItem[]>('GET', '/creator/analytics/me/media', { role: 'creator' })
      : mockOr<ContentPerformanceItem[]>([]),
};

// ---------------------------------------------------------------------------
// Content performance — AnalyticsController @ /analytics/creators/:id/media
// ---------------------------------------------------------------------------

/**
 * Per-post row returned by GET /analytics/creators/:creatorId/media.
 * Brand Surface Audit fix #4 (wiki/reports/brand-feature-audit.md item 4):
 * the route didn't exist on AnalyticsController as of the audit; Vikram is
 * adding it. This shape is what ContentPerformancePanel actually renders
 * (mediaType/postedAt for the row header, reach/impressions/engagementRate
 * for the three stat columns) — treat every field here as the FE's required
 * minimum. Confirm against the real response once
 * wiki/build/brand-fixes-backend.md documents it (not written as of this
 * pass); if the backend adds extra fields, they're additive and don't need a
 * type change here, but if any of these six are missing/renamed the panel
 * needs an update.
 */
export interface ContentPerformanceItem {
  mediaId: string;
  mediaType: string;
  postedAt: string;
  /**
   * Nullable on the wire — `AnalyticsDtos.ContentPerformanceResponse` is
   * `@JsonInclude(NON_NULL)`, so when Meta didn't report reach for a post the
   * key is OMITTED entirely (arrives as `undefined`), never sent as JSON
   * `null`. Typed `| null` so the `?? null`/`== null` consumers in
   * ContentPerformancePanel treat "omitted" and "explicit null" the same way
   * (Priya's brand-fixes review, fix #4).
   */
  reach: number | null;
  /** Same NON_NULL-omission behavior as `reach` — see its doc comment. */
  impressions: number | null;
  engagementRate: number | null;
}

export const contentPerformance = {
  /** GET /analytics/creators/:creatorId/media (AnalyticsController.java). */
  list: (creatorId: string): Promise<ContentPerformanceItem[]> =>
    isLive()
      ? http.request<ContentPerformanceItem[]>('GET', `/analytics/creators/${creatorId}/media`)
      : mockOr<ContentPerformanceItem[]>([]),
};

// ---------------------------------------------------------------------------
// Campaign tracking — UTM links + coupons (CampaignTrackingController + TrackingDtos)
// ---------------------------------------------------------------------------

export interface TrackingLinkResponse {
  id: string;
  campaignId: string;
  collaborationId: string;
  creatorProfileId: string;
  baseUrl: string;
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
  utmContent?: string;
  fullTrackingUrl: string;
  shortUrl?: string;
  clickCount: number;
  uniqueVisitors: number;
  conversionCount: number;
  revenueAttributed: number;
  createdAt: string;
  updatedAt: string;
  expiresAt?: string;
}

export interface CouponResponse {
  id: string;
  campaignId: string;
  creatorProfileId: string;
  code: string;
  discountType: 'percentage' | 'fixed';
  discountValue: number;
  usageLimit?: number;
  usageCount: number;
  expiresAt?: string;
  createdAt: string;
}

export interface CreateTrackingLinkPayload {
  collaborationId: string;
  creatorProfileId: string;
  baseUrl: string;
  platform: string;
}

export interface CreateCouponPayload {
  creatorProfileId: string;
  discountType: 'percentage' | 'fixed';
  discountValue: number;
  usageLimit?: number;
  expiresAt?: string;
}

export const campaignTracking = {
  /** GET /campaigns/:campaignId/tracking-links (CampaignTrackingController.java:85) */
  listTrackingLinks: async (campaignId: string): Promise<TrackingLinkResponse[]> => {
    if (!isLive()) return mockOr<TrackingLinkResponse[]>([]);
    const res = await http.request<{ trackingLinks: TrackingLinkResponse[] }>('GET', `/campaigns/${campaignId}/tracking-links`);
    return res.trackingLinks ?? [];
  },

  /** POST /campaigns/:campaignId/tracking-links (CampaignTrackingController.java:67) */
  createTrackingLink: (campaignId: string, payload: CreateTrackingLinkPayload) =>
    isLive()
      ? http.request<TrackingLinkResponse>('POST', `/campaigns/${campaignId}/tracking-links`, { body: payload })
      : mockOr<TrackingLinkResponse>({
          id: `tl_${Date.now()}`, campaignId, collaborationId: payload.collaborationId,
          creatorProfileId: payload.creatorProfileId, baseUrl: payload.baseUrl,
          utmSource: payload.platform.toLowerCase(), utmMedium: payload.platform.toLowerCase(),
          utmCampaign: campaignId, fullTrackingUrl: `${payload.baseUrl}?utm_source=${payload.platform.toLowerCase()}`,
          clickCount: 0, uniqueVisitors: 0, conversionCount: 0, revenueAttributed: 0,
          createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
        }),

  /** GET /campaigns/:campaignId/coupons (CampaignTrackingController.java:113) */
  listCoupons: async (campaignId: string): Promise<CouponResponse[]> => {
    if (!isLive()) return mockOr<CouponResponse[]>([]);
    const res = await http.request<{ coupons: CouponResponse[] }>('GET', `/campaigns/${campaignId}/coupons`);
    return res.coupons ?? [];
  },

  /** POST /campaigns/:campaignId/coupons (CampaignTrackingController.java:94) */
  createCoupon: (campaignId: string, payload: CreateCouponPayload) =>
    isLive()
      ? http.request<CouponResponse>('POST', `/campaigns/${campaignId}/coupons`, { body: payload })
      : mockOr<CouponResponse>({
          id: `cp_${Date.now()}`, campaignId, creatorProfileId: payload.creatorProfileId,
          code: `SAVE${Math.floor(Math.random() * 9000 + 1000)}`,
          discountType: payload.discountType, discountValue: payload.discountValue,
          usageLimit: payload.usageLimit, usageCount: 0,
          expiresAt: payload.expiresAt, createdAt: new Date().toISOString(),
        }),
};

// ---------------------------------------------------------------------------
// Store integrations — Shopify + WooCommerce (brand-facing)
// ---------------------------------------------------------------------------

/** Matches `IntegrationDtos.StoreProvider` (Java enum) — uppercase, case-sensitive on both wire directions. */
export type StoreProvider = 'SHOPIFY' | 'WOOCOMMERCE';

/** GET /shopify/oauth/authorize (ShopifyDtos.java:11) */
export interface ShopifyAuthorizeResult { authorizationUrl: string; state: string }
/** POST /woocommerce/connect (WooCommerceDtos.java:18) */
export interface WooCommerceConnectResult { connected: boolean; siteUrl: string }
/** GET /integrations/store/status response shape (IntegrationDtos.IntegrationStatusResponse). */
export interface IntegrationStatus {
  connected: boolean;
  provider?: StoreProvider;
  shopDomainOrSiteUrl?: string;
  connectedAt?: string;
}

export const storeIntegrations = {
  /** GET /shopify/oauth/authorize?shop= (ShopifyConnectController.java:64) */
  authorizeShopify: (shop: string) =>
    isLive()
      ? http.request<ShopifyAuthorizeResult>('GET', '/shopify/oauth/authorize', { query: { shop } })
      : mockOr<ShopifyAuthorizeResult>({
          authorizationUrl: `https://${shop}.myshopify.com/admin/oauth/authorize?mock=1`, state: 'mock_state',
        }),

  /** POST /woocommerce/connect (WooCommerceConnectController.java:61) */
  connectWooCommerce: (payload: { siteUrl: string; webhookSecret: string }) =>
    isLive()
      ? http.request<WooCommerceConnectResult>('POST', '/woocommerce/connect', { body: payload })
      : mockOr<WooCommerceConnectResult>({ connected: true, siteUrl: payload.siteUrl }),

  /** GET /integrations/store/status (StoreIntegrationStatusController.java:68) */
  status: () =>
    isLive()
      ? http.request<IntegrationStatus>('GET', '/integrations/store/status')
      : mockOr<IntegrationStatus>({ connected: false }),

  /** DELETE /integrations/store/disconnect?provider=SHOPIFY|WOOCOMMERCE (StoreIntegrationStatusController.java:108) */
  disconnect: (provider: StoreProvider) =>
    isLive()
      ? http.request<{ disconnected: boolean }>('DELETE', '/integrations/store/disconnect', { query: { provider } })
      : mockOr<{ disconnected: boolean }>({ disconnected: true }),
};

// ---------------------------------------------------------------------------
// Reviews — creator ↔ brand (post-COMPLETED collaboration)
// ---------------------------------------------------------------------------

interface ReviewApiResponse {
  id: string;
  collaborationId: string;
  reviewerType: 'CREATOR' | 'BRAND';
  reviewerUserId: string;
  stars: number;
  text: string | null;
  createdAt: string;
}

/** Panel/card view-model. reviewerName/campaignName are display-only (not in the Java DTO). */
export interface ReviewDisplayRecord {
  id: string;
  collaborationId: string;
  reviewerType: 'CREATOR' | 'BRAND';
  reviewerUserId: string;
  stars: number;
  text?: string | null;
  createdAt: string;
  reviewerName?: string | null;
  campaignName?: string | null;
}

export interface CreateReviewPayload {
  collaborationId: string;
  stars: number;
  text?: string;
}

function mapReviewFromApi(r: ReviewApiResponse): ReviewDisplayRecord {
  return { ...r, reviewerName: undefined, campaignName: undefined };
}

const mockReceivedReviews = (about: Role): ReviewDisplayRecord[] => [
  {
    id: 'rv_1', collaborationId: 'deal-done-1',
    reviewerType: about === 'creator' ? 'BRAND' : 'CREATOR',
    reviewerUserId: about === 'creator' ? 'u_1' : 'cr_1',
    stars: 5, text: 'Great collaboration — delivered on time and on brief.',
    createdAt: new Date(Date.now() - 3 * 864e5).toISOString(),
    reviewerName: about === 'creator' ? 'Nykaa Fashion' : 'Priya Sharma',
    campaignName: 'Winter Collection',
  },
];

export const creatorReviews = {
  /** POST /creator/reviews (CreatorReviewController.java:36) */
  create: async (payload: CreateReviewPayload): Promise<ReviewDisplayRecord> => {
    if (!isLive()) return mockOr(mapReviewFromApi({
      id: 'rv_new', collaborationId: payload.collaborationId, reviewerType: 'CREATOR',
      reviewerUserId: 'cr_1', stars: payload.stars, text: payload.text ?? null, createdAt: new Date().toISOString(),
    }));
    const row = await http.request<ReviewApiResponse>('POST', '/creator/reviews', { role: 'creator', body: payload });
    return mapReviewFromApi(row);
  },

  /** GET /creator/reviews/received (CreatorReviewController.java:45) */
  listReceived: async (): Promise<ReviewDisplayRecord[]> => {
    if (!isLive()) return mockOr(mockReceivedReviews('creator'));
    const rows = await http.request<ReviewApiResponse[]>('GET', '/creator/reviews/received', { role: 'creator' });
    return rows.map(mapReviewFromApi);
  },

  /**
   * POST /creator/reviews/:id/flag (CreatorReviewController.java:51) — creator flags a received
   * review as inappropriate/inaccurate for moderator attention. `reason` is required (max 255 chars).
   */
  flag: (reviewId: string, reason: string) =>
    isLive()
      ? http.request<{ flagId: string; status: string }>(
          'POST', `/creator/reviews/${reviewId}/flag`, { role: 'creator', body: { reason } },
        )
      : mockOr<{ flagId: string; status: string }>({ flagId: 'flag_new', status: 'PENDING' }),
};

export const brandReviews = {
  /** POST /brand/reviews (BrandReviewController.java:34) */
  create: async (payload: CreateReviewPayload): Promise<ReviewDisplayRecord> => {
    if (!isLive()) return mockOr(mapReviewFromApi({
      id: 'rv_new', collaborationId: payload.collaborationId, reviewerType: 'BRAND',
      reviewerUserId: 'u_1', stars: payload.stars, text: payload.text ?? null, createdAt: new Date().toISOString(),
    }));
    const row = await http.request<ReviewApiResponse>('POST', '/brand/reviews', { role: 'brand', body: payload });
    return mapReviewFromApi(row);
  },

  /** GET /brand/reviews/received (BrandReviewController.java). */
  listReceived: async (): Promise<ReviewDisplayRecord[]> => {
    if (!isLive()) return mockOr(mockReceivedReviews('brand'));
    const rows = await http.request<ReviewApiResponse[]>('GET', '/brand/reviews/received', { role: 'brand' });
    return rows.map(mapReviewFromApi);
  },

  /**
   * POST /brand/reviews/:id/flag (BrandReviewController.java:51) — flag a received review as
   * inappropriate/inaccurate for moderator attention. `reason` is required (max 255 chars).
   */
  flag: (reviewId: string, reason: string) =>
    isLive()
      ? http.request<{ flagId: string; status: string }>(
          'POST', `/brand/reviews/${reviewId}/flag`, { role: 'brand', body: { reason } },
        )
      : mockOr<{ flagId: string; status: string }>({ flagId: 'flag_new', status: 'PENDING' }),
};

// ---------------------------------------------------------------------------
// Meta OAuth — Instagram/Facebook connect (creator). Server-managed CSRF state.
// ---------------------------------------------------------------------------

export interface MetaAuthorizeResponse { authorizationUrl: string; state: string }
export interface MetaCallbackResponse {
  connected: boolean;
  /**
   * CR-104 — the REAL scopes Meta's `/me/permissions` reported as granted for this token, never
   * just the requested set (a creator can decline individual permissions in the consent dialog).
   * `null` means influora-api's permissions check itself failed (network/Meta API issue) — the
   * true grant state is unknown; render that distinctly, never as "connected with zero scopes"
   * and never by assuming the full requested set was granted.
   */
  grantedScopes: string[] | null;
  /** 'personal' means OAuth succeeded but the linked IG is not a Business/Creator account —
   *  creator co-pilot cannot use it (`connected` is `false` in this case). Absent/undefined on
   *  the ordinary success path. Creator-copilot-API-CONTRACT.md §4.2. */
  accountType?: 'personal' | 'business';
}
export interface MetaConnectionState {
  connected: boolean;
  /** CR-104 — `null` = permissions could not be verified (see `MetaCallbackResponse.grantedScopes`);
   *  UI must render this as "unknown", not silently coerce it to an empty or full list. */
  scopes: string[] | null;
  /** null = unknown (never resolved a callback yet). Persisted alongside `connected`/`scopes` in
   *  the same localStorage mirror (`META_CONNECTION_KEY`). Creator-copilot-API-CONTRACT.md §4.2. */
  accountType: 'personal' | 'business' | null;
}
/** Response for GET /meta/oauth/status (MetaOAuthController.java:116, CR-106) — the real
 *  backend truth, as opposed to the localStorage mirror in `MetaConnectionState`. No
 *  `accountType` here (that's only resolved during the OAuth callback). */
export interface MetaConnectionStatusResponse {
  connected: boolean;
  handle?: string;
  followers?: number;
  connectedAt?: string;
  grantedScopes: string[];
}

const META_CONNECTION_KEY = 'meta_connection';
/**
 * Mirrors MetaOAuthService.REQUIRED_SCOPES (MetaOAuthService.java:28-38).
 * CR-115 — pages_read_engagement dropped; FacebookPageClient.getPage(), the only method that
 * scope backed, had zero production callers.
 */
const META_REQUIRED_SCOPES = ['instagram_basic', 'instagram_manage_insights', 'pages_show_list'];

export const metaOAuth = {
  /** GET /meta/oauth/authorize (MetaOAuthController.java:54) */
  authorize: () =>
    isLive()
      ? http.request<MetaAuthorizeResponse>('GET', '/meta/oauth/authorize', { role: 'creator' })
      : mockOr<MetaAuthorizeResponse>({
          authorizationUrl: `${window.location.origin}/creator/settings/meta/callback?code=mock_code&state=mock_state`,
          state: 'mock_state',
        }),

  /** GET /meta/oauth/callback?code=&state= (MetaOAuthController.java:66) */
  callback: (code: string, state: string) =>
    isLive()
      ? http.request<MetaCallbackResponse>('GET', '/meta/oauth/callback', { role: 'creator', query: { code, state } })
      : mockOr<MetaCallbackResponse>({ connected: true, grantedScopes: META_REQUIRED_SCOPES }),

  /**
   * GET /meta/oauth/status (MetaOAuthController.java:116, CR-106) — CR-107 fix: the real
   * backend re-verification `connected-accounts.tsx` was missing. Callers should reconcile
   * the result into the localStorage mirror via `setLocalConnectionState` rather than trusting
   * `getLocalConnectionState` alone, which never expires or notices a revoke/disconnect.
   */
  status: (): Promise<MetaConnectionStatusResponse> => {
    if (isLive()) {
      return http.request<MetaConnectionStatusResponse>('GET', '/meta/oauth/status', { role: 'creator' });
    }
    // Demo/mock mode has no backend to verify against — mirror whatever the localStorage
    // mock-connect flow already recorded so a completed mock OAuth still shows connected.
    // `MetaConnectionStatusResponse.grantedScopes` is never null (the real backend's getStatus
    // always resolves a concrete, possibly-empty list) — CR-104's null/"unknown" state only
    // exists transiently between an OAuth callback and the next status re-verification, so
    // coerce it here rather than widening this response type to match.
    const local = metaOAuth.getLocalConnectionState();
    return mockOr<MetaConnectionStatusResponse>({ connected: local.connected, grantedScopes: local.scopes ?? [] });
  },

  /**
   * POST /meta/oauth/disconnect (MetaOAuthController.java:129, CR-102/F-0115). The route and
   * the correctly-creator-scoped revoke were already built server-side; nothing in the frontend
   * ever called it — there was no way for a creator to disconnect their Meta/Instagram account
   * anywhere in the product. Clears the local mirror too so the UI reflects the disconnect
   * immediately rather than waiting on the next `status()` re-verification.
   */
  disconnect: (): Promise<{ disconnected: boolean }> => {
    if (isLive()) {
      return http
        .request<{ disconnected: boolean }>('POST', '/meta/oauth/disconnect', { role: 'creator' })
        .then((res) => {
          metaOAuth.setLocalConnectionState(false, [], null);
          return res;
        });
    }
    metaOAuth.setLocalConnectionState(false, [], null);
    return mockOr<{ disconnected: boolean }>({ disconnected: true });
  },

  getLocalConnectionState: (): MetaConnectionState => {
    try {
      const raw = localStorage.getItem(META_CONNECTION_KEY);
      if (!raw) return { connected: false, scopes: [], accountType: null };
      const p = JSON.parse(raw) as Partial<MetaConnectionState>;
      // CR-104 — `scopes` must stay `null` when that's what was stored (permissions
      // unverifiable). `??` would collapse a real `null` back to `[]`, indistinguishable from
      // "verified, zero scopes granted"; only an absent/undefined key defaults to `[]`.
      return {
        connected: !!p.connected,
        scopes: p.scopes === undefined ? [] : p.scopes,
        accountType: p.accountType ?? null,
      };
    } catch {
      return { connected: false, scopes: [], accountType: null };
    }
  },

  /** `accountType` optional + defaults to `null` so existing call sites (pre-dating this field)
   *  keep compiling untouched; pass it explicitly once a caller has a callback's `accountType`.
   *  `scopes: null` (CR-104) persists the "permissions could not be verified" state as-is. */
  setLocalConnectionState: (connected: boolean, scopes: string[] | null, accountType: 'personal' | 'business' | null = null): void => {
    localStorage.setItem(META_CONNECTION_KEY, JSON.stringify({ connected, scopes, accountType }));
  },

  /**
   * CR-54/CR-65 — single shared "where to return after the OAuth round-trip" marker, one key
   * instead of a proliferating set of single-purpose ones (the same duplication class CR-34
   * already paid for once in this codebase). The onboarding wizard keeps its own dedicated
   * `creator_onboarding_meta_resume` flag (api.ts callers of it are unchanged) because it also
   * drives wizard-step-specific logic on return, not just navigation — this key is for every
   * other initiator (Co-pilot prompts, the Deal Room's Connect Instagram button, Settings) that
   * just wants "send the creator back to where they started."
   */
  setConnectReturnTo: (path: string): void => {
    sessionStorage.setItem('creator_meta_connect_return_to', path);
  },
  /** Read-and-clear — a stale marker must never misroute a later, unrelated connect attempt. */
  consumeConnectReturnTo: (): string | null => {
    const path = sessionStorage.getItem('creator_meta_connect_return_to');
    sessionStorage.removeItem('creator_meta_connect_return_to');
    return path;
  },
  /**
   * F-0168 — the read-and-clear in `consumeConnectReturnTo` only protects against a stale
   * marker being seen by a *later mount* of the callback page; it does nothing for a marker
   * left behind by an attempt that never reaches that page at all (the panel's `authorize()`
   * call throws before redirect, or the creator abandons the Meta dialog via browser Back).
   * Every initiator that does NOT itself call `setConnectReturnTo` — i.e. every plain "just
   * send me back to Settings" entry point — must call this first, so a leftover marker from an
   * abandoned deal-room/Co-pilot attempt can never misroute this unrelated connect.
   */
  clearConnectReturnTo: (): void => {
    sessionStorage.removeItem('creator_meta_connect_return_to');
  },
};

// ---------------------------------------------------------------------------
// Creator coupons / affiliate earnings / campaign browse (creator role)
// ---------------------------------------------------------------------------

export interface CreatorCouponResponse {
  id: string;
  campaignId: string;
  campaignName: string;
  brandName: string;
  code: string;
  discountType: 'percentage' | 'fixed' | string;
  discountValue: number;
  usageLimit: number | null;
  usageCount: number;
  expiresAt: string | null;
  createdAt: string;
  /**
   * Raw brand-site URL with UTM params (`CampaignLinkService`'s `fullTrackingUrl`). Posting THIS
   * URL directly does not get clicks counted — see `redirectUrl` below.
   */
  trackingUrl: string | null;
  /**
   * Backend gap (tracking-subsystem-ruling.md Q3): `CreatorCouponListItem` does not expose the
   * `/track/click/{utmCampaignId}` redirect URL yet — only the raw `trackingUrl`. This optional
   * field is forward-declared so the UI picks it up the moment Vikram adds it (redirect URL, or
   * the `utmCampaignId` needed to build one), with zero further frontend changes. Until then it is
   * always `undefined` from the live API.
   */
  redirectUrl?: string | null;
}

export interface AffiliateEarningRow {
  id: string;
  campaignId: string;
  campaignName: string;
  brandName: string;
  redemptionId: string;
  orderId: string;
  orderTotal: number;
  commissionAmount: number;
  currency: string;
  status: string;
  settlementBatchId: string | null;
  createdAt: string;
  settledAt: string | null;
}
export interface AffiliateEarningsSummary {
  thisMonthSales: number;
  thisMonthRevenue: number;
  thisMonthCommission: number;
  unsettledCommission: number;
  currency: string;
}
export interface CreatorAffiliateEarningsResponse {
  earnings: AffiliateEarningRow[];
  summary: AffiliateEarningsSummary;
  /** CR-83 — pagination metadata; summary always reflects the full history, not just this page. */
  page: number;
  limit: number;
  totalElements: number;
  hasMore: boolean;
}

export interface CreatorCampaignBrandSummary {
  workspaceId: string;
  name: string;
  logoUrl?: string | null;
  /**
   * VER-1 (BrandF.md §105/§115, PR-2): mirrors CreatorCampaignDtos.BrandSummary.verificationStatus
   * (CreatorCampaignMapper#toBrand — real Workspace.verificationStatus, not a fabricated default).
   * Was on the wire with no matching frontend field — this type had no way to read it.
   */
  verificationStatus?: VerificationStatus | null;
}
export interface CreatorCampaignBudget { min: number; max: number; currency: string }
export interface CreatorCampaignListItem {
  id: string;
  title: string;
  description: string;
  brand?: CreatorCampaignBrandSummary;
  budget?: CreatorCampaignBudget;
  platforms: string[];
  requirements: string[];
  applicationDeadline: string | null;
  startDate: string | null;
  endDate: string | null;
  maxCollaborators: number | null;
  applicationStatus: string | null;
  createdAt: string;
}
export interface CreatorCampaignDetail extends Omit<CreatorCampaignListItem, 'requirements'> {
  objectives: string[];
  contentTypes: string[];
  requirements: string[];
  hashtags: string[];
  brandGuidelines: string | null;
}
export interface CreatorCampaignApplyResponse { collaborationId: string; status: string; appliedAt: string }
export interface CreatorCampaignBrowseParams {
  niche?: string; platform?: string; budgetMin?: number; budgetMax?: number; page?: number; limit?: number;
}

const mockCreatorCoupons: CreatorCouponResponse[] = [
  {
    id: 'cp_1', campaignId: 'camp_1', campaignName: 'Summer Collection Launch',
    brandName: 'Nykaa Fashion', code: 'PRIYA20', discountType: 'percentage',
    discountValue: 20, usageLimit: 500, usageCount: 143,
    expiresAt: new Date(Date.now() + 30 * 864e5).toISOString(),
    createdAt: new Date(Date.now() - 10 * 864e5).toISOString(),
    trackingUrl: 'https://nykaafashion.com/?coupon=PRIYA20',
  },
  {
    id: 'cp_2', campaignId: 'camp_2', campaignName: 'Earbuds Launch',
    brandName: 'boAt', code: 'BOAT15PRIYA', discountType: 'percentage',
    discountValue: 15, usageLimit: null, usageCount: 88,
    expiresAt: null, createdAt: new Date(Date.now() - 20 * 864e5).toISOString(),
    trackingUrl: null,
  },
];

export const creatorCoupons = {
  /** GET /creator/coupons (CreatorCouponController.java:29) */
  list: () =>
    isLive()
      ? http.request<CreatorCouponResponse[]>('GET', '/creator/coupons', { role: 'creator' })
      : mockOr<CreatorCouponResponse[]>(mockCreatorCoupons),
};

export const affiliateEarnings = {
  /**
   * GET /creator/affiliate-earnings (CreatorAffiliateEarningController.java:28)
   * CR-83 — page/limit added; omit both for the server's own defaults (page 0, size 20).
   */
  get: (page?: number, limit?: number) =>
    isLive()
      ? http.request<CreatorAffiliateEarningsResponse>('GET', '/creator/affiliate-earnings', {
          role: 'creator',
          query: { page, limit },
        })
      : mockOr<CreatorAffiliateEarningsResponse>({
          earnings: [],
          summary: { thisMonthSales: 0, thisMonthRevenue: 0, thisMonthCommission: 0, unsettledCommission: 0, currency: 'INR' },
          page: 0,
          limit: limit ?? 20,
          totalElements: 0,
          hasMore: false,
        }),
};

/**
 * Row returned by `GET /creator/applications` (CreatorApplicationController, new
 * 2026-07-24). Source of truth is `Collaboration` rows where `source = APPLICATION`
 * (CTO arbitration, wiki/build/my-applications-plan-2026-07-24.md) — distinct from
 * the loose `applicationStatus` on `CreatorCampaignListItem`, which only reflects
 * the browse-campaigns path. `dealId` is the collaboration id and is always present
 * (it's the same row, whatever stage it's in) — it's what the deal room route needs.
 */
export interface CreatorApplicationRow {
  campaignId: string;
  campaignTitle: string;
  brandName: string;
  brandLogoUrl?: string;
  appliedAt: string;
  status: string;
  statusLabel: string;
  agreedRate?: number;
  currency?: string;
  dealId: string;
}

export interface CreatorApplicationsPage {
  applications: CreatorApplicationRow[];
  meta: { page: number; limit: number; total: number; hasMore: boolean };
}

export const creatorApplications = {
  /**
   * GET /creator/applications (CreatorApplicationController.java:31) — data=CreatorApplicationListItem[],
   * envelope.meta=page info. CR-58 — server paginates (defaultValue limit=50); uses `requestWithMeta`
   * (not `request`) so callers can page through creators with 50+ applications instead of the
   * list silently truncating with no notice — mirrors `creatorCampaigns.browse`.
   */
  list: async (page = 1, limit = 50): Promise<CreatorApplicationsPage> => {
    if (!isLive()) return { applications: [], meta: { page, limit, total: 0, hasMore: false } };
    const { data, meta } = await http.requestWithMeta<CreatorApplicationRow[]>('GET', '/creator/applications', {
      role: 'creator',
      query: { page, limit },
    });
    return {
      applications: data,
      meta: {
        page: meta?.page ?? page,
        limit: meta?.limit ?? limit,
        total: meta?.total ?? data.length,
        hasMore: Boolean(meta?.hasMore),
      },
    };
  },
};

/**
 * CR-57 — mock/demo mode previously returned an empty list from `browse` and `null` from `get`,
 * so the entire browse → detail → apply discovery loop showed only empty/not-found states in a
 * demo build — there was nothing to click through. Illustrative campaigns, same pattern as the
 * other mock data in this file (e.g. `mockCreatorProfileSelf`) — clearly synthetic, live mode
 * untouched.
 */
const mockCreatorCampaigns: CreatorCampaignListItem[] = [
  {
    id: 'camp_mock_1',
    title: 'Monsoon Skincare Launch',
    description: 'Looking for beauty creators to showcase our new hydrating serum line ahead of monsoon season.',
    brand: { workspaceId: 'ws_mock_1', name: 'Glow Naturals' },
    budget: { min: 15000, max: 35000, currency: 'INR' },
    platforms: ['Instagram', 'YouTube'],
    requirements: ['1 Reel', '2 Stories'],
    applicationDeadline: new Date(Date.now() + 12 * 864e5).toISOString(),
    startDate: new Date(Date.now() + 20 * 864e5).toISOString(),
    endDate: new Date(Date.now() + 50 * 864e5).toISOString(),
    maxCollaborators: 8,
    applicationStatus: null,
    createdAt: new Date(Date.now() - 3 * 864e5).toISOString(),
  },
  {
    id: 'camp_mock_2',
    title: 'Festive Fashion Edit',
    description: 'Showcase our festive collection through styled looks and try-on hauls.',
    brand: { workspaceId: 'ws_mock_2', name: 'Nykaa Fashion' },
    budget: { min: 20000, max: 60000, currency: 'INR' },
    platforms: ['Instagram'],
    requirements: ['1 Reel', 'Carousel post'],
    applicationDeadline: new Date(Date.now() + 6 * 864e5).toISOString(),
    startDate: new Date(Date.now() + 14 * 864e5).toISOString(),
    endDate: new Date(Date.now() + 40 * 864e5).toISOString(),
    maxCollaborators: 12,
    applicationStatus: null,
    createdAt: new Date(Date.now() - 1 * 864e5).toISOString(),
  },
  {
    id: 'camp_mock_3',
    title: 'True Wireless Earbuds — Sound Test',
    description: 'Put our new earbuds through a real-world sound and battery-life test for your audience.',
    brand: { workspaceId: 'ws_mock_3', name: 'boAt' },
    budget: { min: 10000, max: 25000, currency: 'INR' },
    platforms: ['YouTube', 'Instagram'],
    requirements: ['1 Long-form video', '1 Reel cutdown'],
    applicationDeadline: new Date(Date.now() + 18 * 864e5).toISOString(),
    startDate: new Date(Date.now() + 25 * 864e5).toISOString(),
    endDate: new Date(Date.now() + 55 * 864e5).toISOString(),
    maxCollaborators: 5,
    applicationStatus: null,
    createdAt: new Date(Date.now() - 5 * 864e5).toISOString(),
  },
];

function mockCreatorCampaignDetail(id: string): CreatorCampaignDetail | null {
  const listItem = mockCreatorCampaigns.find((c) => c.id === id);
  if (!listItem) return null;
  const { requirements, ...rest } = listItem;
  return {
    ...rest,
    objectives: ['Drive awareness', 'Generate authentic content for paid amplification'],
    contentTypes: requirements,
    requirements,
    hashtags: ['#ad', `#${rest.brand?.name.replace(/\s+/g, '')}`],
    brandGuidelines: 'Keep tone authentic and conversational — avoid hard-sell language.',
  };
}

export const creatorCampaigns = {
  /** GET /creator/campaigns (CreatorCampaignController.java:40) — data=items, envelope.meta=page info */
  browse: async (params: CreatorCampaignBrowseParams = {}) => {
    if (!isLive())
      return mockOr<{ campaigns: CreatorCampaignListItem[]; meta: { hasMore: boolean } }>({
        campaigns: mockCreatorCampaigns, meta: { hasMore: false },
      });
    const { data, meta } = await http.requestWithMeta<CreatorCampaignListItem[]>('GET', '/creator/campaigns', {
      role: 'creator',
      query: {
        niche: params.niche, platform: params.platform, budgetMin: params.budgetMin,
        budgetMax: params.budgetMax, page: params.page, limit: params.limit,
      },
    });
    return { campaigns: data, meta: { hasMore: Boolean(meta?.hasMore) } };
  },

  /** GET /creator/campaigns/:id (CreatorCampaignController.java:54) */
  get: (id: string) =>
    isLive()
      ? http.request<CreatorCampaignDetail>('GET', `/creator/campaigns/${id}`, { role: 'creator' })
      : mockOr<CreatorCampaignDetail | null>(mockCreatorCampaignDetail(id)),

  /** POST /creator/campaigns/:id/apply (CreatorCampaignController.java:60) */
  apply: (id: string, body?: { message?: string }) =>
    isLive()
      ? http.request<CreatorCampaignApplyResponse>('POST', `/creator/campaigns/${id}/apply`, { role: 'creator', body })
      : mockOr<CreatorCampaignApplyResponse>({ collaborationId: 'col_new', status: 'APPLIED', appliedAt: new Date().toISOString() }),
};

// ---------------------------------------------------------------------------
// Creator deliverables — deal-room list (CreatorDeliverableController @ /creator/deliverables)
// ---------------------------------------------------------------------------

/** Full backend DeliverableStatus vocabulary (DeliverableStatus.java) — broader than the FE union. */
export type CreatorDeliverableRowStatus =
  | 'PENDING'
  | 'DRAFT'
  | 'SUBMITTED'
  | 'REVISION_REQUESTED'
  | 'RESUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'POSTED'
  | 'METRICS_REPORTED'
  | 'VERIFIED';

export interface CreatorDeliverableListItem {
  id: string;
  title: string;
  description: string;
  status: CreatorDeliverableRowStatus;
  completed: boolean;
  currentRevision: number | null;
  maxRevisions: number | null;
}

/** POST /creator/deliverables/:id/metrics body — MetricsPayload (CreatorDeliverableDtos.java:65). */
export interface CreatorDeliverableMetricsPayload {
  likes?: number;
  comments?: number;
  shares?: number;
  views?: number;
  reach?: number;
  impressions?: number;
  saves?: number;
}

/** POST /creator/deliverables/:id/upload response — UploadResponse (CreatorDeliverableDtos.java:23). */
export interface CreatorDeliverableUploadFile {
  id: string;
  fileType: string;
  fileName: string;
  url: string;
  thumbnailUrl: string | null;
  fileSize: number | null;
  durationSeconds: number | null;
}
export interface CreatorDeliverableUploadResponse {
  versionId: string;
  versionNumber: number;
  files: CreatorDeliverableUploadFile[];
  status: CreatorDeliverableRowStatus;
}

/** GET /creator/deliverables/:id/status response — DeliverableStatusResponse (CreatorDeliverableDtos.java:29). */
export interface CreatorDeliverableStatusResponse {
  id: string;
  collaborationId: string;
  title: string;
  status: CreatorDeliverableRowStatus;
  versionNumber: number;
  revisionCount: number;
  actions: { canUploadNewVersion: boolean; canSubmit: boolean; canReportMetrics: boolean };
  /** verified-analytics-0804 — cached metric source / verification state. */
  metricSource: CreatorDeliverableMetricSource | null;
  lastVerifiedAt: string | null;
  metaConnected: boolean;
}

/** Metric provenance — the honesty signal (DeliverableMetric.source). */
export type CreatorDeliverableMetricSource = 'PLATFORM_VERIFIED' | 'CREATOR_REPORTED';

/** DeliverableVerificationService.Outcome (verified-analytics-0804). */
export type CreatorDeliverableVerifyOutcome =
  | 'VERIFIED'
  | 'FALLBACK_NO_POST_URL'
  | 'FALLBACK_NO_MILESTONE'
  | 'FALLBACK_UNRECOGNIZED_URL'
  | 'FALLBACK_YOUTUBE_UNSUPPORTED'
  | 'FALLBACK_NO_TOKEN'
  | 'FALLBACK_TOKEN_EXPIRED'
  | 'FALLBACK_RATE_LIMITED'
  | 'FALLBACK_NOT_FOUND'
  | 'FALLBACK_API_ERROR'
  | 'FALLBACK_DATA_INTEGRITY';

/**
 * POST /creator/deliverables/:id/verify response — VerificationStateResponse
 * (CreatorDeliverableDtos.java). `manualFallbackAllowed` is true ONLY when Meta genuinely failed
 * for a connected account — the sole condition under which the manual form may open.
 */
export interface CreatorDeliverableVerificationState {
  deliverableId: string;
  outcome: CreatorDeliverableVerifyOutcome;
  metricSource: CreatorDeliverableMetricSource | null;
  reach: number | null;
  impressions: number | null;
  engagements: number | null;
  lastVerifiedAt: string | null;
  metaConnected: boolean;
  manualFallbackAllowed: boolean;
}

/** POST /creator/deliverables/:id/mark-posted response — MarkPostedResponse (CreatorDeliverableDtos.java:96). */
export interface CreatorDeliverableMarkPostedResponse {
  id: string;
  status: CreatorDeliverableRowStatus;
  postUrl: string;
  postedAt: string;
}

/** POST /creator/deliverables/:id/metrics response — MetricsReportResponse (CreatorDeliverableDtos.java:81). */
export interface CreatorDeliverableMetricsReportResponse {
  deliverableId: string;
  status: CreatorDeliverableRowStatus;
  metrics: CreatorDeliverableMetricsPayload;
  engagementRate: number | null;
  verificationStatus: string;
  message: string;
}

/** POST /creator/deliverables/:id/proof response — ProofUploadResponse (CreatorDeliverableDtos.java:90). */
export interface CreatorDeliverableProofResponse {
  id: string;
  key: string;
  url: string;
  uploadedAt: string;
  urlExpiresAt: string;
}

export const creatorDeliverables = {
  /** GET /creator/deliverables?collaboration_id= (CreatorDeliverableController.java:44) */
  listForDeal: (collaborationId: string) =>
    isLive()
      ? http.request<CreatorDeliverableListItem[]>('GET', '/creator/deliverables', {
          role: 'creator', query: { collaboration_id: collaborationId },
        })
      : mockOr<CreatorDeliverableListItem[]>([]),

  /**
   * GET /creator/deliverables/bulk?collaboration_ids= (CreatorDeliverableController.java, CR-51).
   * Batched counterpart to `listForDeal` — one request for many deal ids instead of one request
   * per deal (the N+1 the creator dashboard's pending-deliverable rollup used to trigger on load).
   * Returns a map keyed by collaboration id; ids the caller isn't allowed to see are simply absent
   * from the map, so a missing key means "treat as no deliverables" (same as the mock fallback).
   */
  listForDeals: (collaborationIds: string[]) =>
    isLive() && collaborationIds.length > 0
      ? http.request<Record<string, CreatorDeliverableListItem[]>>(
          'GET',
          '/creator/deliverables/bulk',
          { role: 'creator', query: { collaboration_ids: collaborationIds.join(',') } },
        )
      : mockOr<Record<string, CreatorDeliverableListItem[]>>({}),

  /**
   * POST /creator/deliverables/:id/upload — multipart (CreatorDeliverableController.java:55).
   * Part name is `files` (list, required) + optional `thumbnail`; caption/hashtags/creatorNotes
   * are optional form fields. Server validates MIME by magic bytes, caps size, scans for malware
   * and sanitizes filenames — client-side checks are UX-only. Must run BEFORE `deliverables.submit`.
   */
  upload: (
    deliverableId: string,
    files: File[],
    opts: { thumbnail?: File; caption?: string; hashtags?: string[]; creatorNotes?: string } = {},
  ) => {
    if (!isLive()) {
      return mockOr<CreatorDeliverableUploadResponse>({
        versionId: `mock_${deliverableId}`,
        versionNumber: 1,
        files: [],
        status: 'DRAFT' as CreatorDeliverableRowStatus,
      });
    }
    const formData = new FormData();
    files.forEach((f) => formData.append('files', f));
    if (opts.thumbnail) formData.append('thumbnail', opts.thumbnail);
    if (opts.caption) formData.append('caption', opts.caption);
    if (opts.creatorNotes) formData.append('creatorNotes', opts.creatorNotes);
    (opts.hashtags ?? []).forEach((h) => formData.append('hashtags', h));
    return http.uploadForm<CreatorDeliverableUploadResponse>(
      `/creator/deliverables/${deliverableId}/upload`,
      formData,
      'creator',
    );
  },

  /** GET /creator/deliverables/:id/status (CreatorDeliverableController.java:70) */
  getStatus: (deliverableId: string) =>
    isLive()
      ? http.request<CreatorDeliverableStatusResponse>(
          'GET',
          `/creator/deliverables/${deliverableId}/status`,
          { role: 'creator' },
        )
      : mockOr<CreatorDeliverableStatusResponse>({
          id: deliverableId,
          collaborationId: 'mock_deal',
          title: 'Instagram Reel',
          status: 'APPROVED' as CreatorDeliverableRowStatus,
          versionNumber: 1,
          revisionCount: 0,
          actions: { canUploadNewVersion: false, canSubmit: false, canReportMetrics: true },
          metricSource: null,
          lastVerifiedAt: null,
          metaConnected: false,
        }),

  /**
   * POST /creator/deliverables/:id/verify — on-demand Meta verification
   * (CreatorDeliverableController.java, verified-analytics-0804). Runs the same verification the 6h
   * batch job runs and returns the live outcome + verified numbers + whether the manual form may open.
   */
  verifyNow: (deliverableId: string) =>
    isLive()
      ? http.request<CreatorDeliverableVerificationState>(
          'POST',
          `/creator/deliverables/${deliverableId}/verify`,
          { role: 'creator' },
        )
      : mockOr<CreatorDeliverableVerificationState>({
          deliverableId,
          outcome: 'VERIFIED',
          metricSource: 'PLATFORM_VERIFIED',
          reach: 48200,
          impressions: 61300,
          engagements: 7850,
          lastVerifiedAt: '2026-08-04T00:00:00Z',
          metaConnected: true,
          manualFallbackAllowed: false,
        }),

  /** POST /creator/deliverables/:id/mark-posted — DPF-3 live post URL (CreatorDeliverableController.java:104) */
  markPosted: (deliverableId: string, livePostUrl: string) =>
    isLive()
      ? http.request<CreatorDeliverableMarkPostedResponse>(
          'POST',
          `/creator/deliverables/${deliverableId}/mark-posted`,
          { body: { livePostUrl }, role: 'creator' },
        )
      : mockOr<CreatorDeliverableMarkPostedResponse>({
          id: deliverableId,
          status: 'POSTED' as CreatorDeliverableRowStatus,
          postUrl: livePostUrl,
          postedAt: '2026-08-04T00:00:00Z',
        }),

  /** POST /creator/deliverables/:id/metrics — self-reported performance (CreatorDeliverableController.java:86) */
  reportMetrics: (
    deliverableId: string,
    payload: {
      metrics: CreatorDeliverableMetricsPayload;
      proofScreenshots?: string[];
      reportedDaysAfterPosting?: number;
    },
  ) =>
    isLive()
      ? http.request<CreatorDeliverableMetricsReportResponse>(
          'POST',
          `/creator/deliverables/${deliverableId}/metrics`,
          { body: payload, role: 'creator' },
        )
      : mockOr<CreatorDeliverableMetricsReportResponse>({
          deliverableId,
          status: 'METRICS_REPORTED' as CreatorDeliverableRowStatus,
          metrics: payload.metrics,
          engagementRate: null,
          verificationStatus: 'PENDING',
          message: 'Metrics recorded',
        }),

  /**
   * POST /creator/deliverables/:id/proof — multipart, part name `screenshot`
   * (CreatorDeliverableController.java:95). Ownership-bound proof of posting.
   */
  uploadProof: (deliverableId: string, screenshot: File) => {
    if (!isLive()) {
      return mockOr<CreatorDeliverableProofResponse>({
        id: `mock_${deliverableId}`,
        key: `mock_${screenshot.name}`,
        url: URL.createObjectURL(screenshot),
        uploadedAt: '2026-08-04T00:00:00Z',
        urlExpiresAt: '2026-08-05T00:00:00Z',
      });
    }
    const formData = new FormData();
    formData.append('screenshot', screenshot);
    return http.uploadForm<CreatorDeliverableProofResponse>(
      `/creator/deliverables/${deliverableId}/proof`,
      formData,
      'creator',
    );
  },
};

// ---------------------------------------------------------------------------
// Disputes — brand + creator tracking (DealController open + BrandDisputeController)
// ---------------------------------------------------------------------------

/** DisputeStatus enum values (DisputeStatus.java) — the real backend vocabulary. */
export type DisputeLifecycleStatus =
  | 'OPEN'
  | 'UNDER_REVIEW'
  | 'RESOLVED_BRAND'
  | 'RESOLVED_CREATOR'
  | 'RESOLVED_SPLIT';

/**
 * Row rendered by the brand/creator dispute pages.
 *
 * Brand side (P2-14): live mode calls the real `GET /brand/disputes/list`
 * (BrandDisputeController → DisputeService#listDisplayForBrand), whose
 * `DisputeListItemResponse` DTO matches this shape field-for-field —
 * `disputeStatus`/`openedAt`/`reason` are always populated, so the page's
 * "partial data" banner no longer fires in live mode.
 *
 * Creator side (2026-07-23): now wired to the real `GET /creator/disputes`
 * (CreatorDisputeController → DisputeService#listDisplayForCreator, scoped
 * server-side to the calling creator via `creatorContext.requireCreator` +
 * `collaboration.creatorId = principal.getUserId()`), whose response matches
 * this shape field-for-field the same way the brand endpoint does — the
 * page's "partial data" banner no longer fires in live mode.
 */
export interface DisputeRow {
  collaborationId: string;
  campaignName: string;
  counterpartyName: string;
  dealValue: number;
  currency: 'INR' | 'USD';
  disputeStatus?: DisputeLifecycleStatus;
  openedAt?: string;
  reason?: string;
  resolutionNotes?: string;
  resolvedAt?: string;
}
export type BrandDisputeRow = DisputeRow;
export type CreatorDisputeRow = DisputeRow;

/** Demo fixtures (mock mode only) — live mode calls the real GET /{role}/disputes(/list) endpoint. */
const mockDisputeRows = (role: Role): DisputeRow[] => [
  {
    collaborationId: 'deal-disputed-1',
    campaignName: 'Summer Fashion Campaign',
    counterpartyName: role === 'creator' ? 'Luxe Apparel' : 'Priya Sharma',
    dealValue: 85000,
    currency: 'INR',
    disputeStatus: 'UNDER_REVIEW',
    openedAt: new Date(Date.now() - 5 * 864e5).toISOString(),
    reason: 'Deliverables approved but payment was not released within the agreed window.',
  },
];

const mockEligibleDeals: Deal[] = [
  {
    id: 'deal-eligible-1', campaignId: 'camp-1', campaignName: 'Winter Collection',
    counterpartyId: 'b_1', counterpartyName: 'Nykaa Fashion', status: 'IN_PROGRESS',
    dealValue: 60000, currency: 'INR', unreadCount: 0,
    deliverablesDone: 1, deliverablesTotal: 3, escrowFunded: true,
  },
];

export const creatorDisputes = {
  /**
   * GET /creator/disputes (CreatorDisputeController.java, 2026-07-23) — real dispute list with
   * display fields (campaign name, brand name, deal value), scoped to the calling creator
   * server-side via DisputeService#listDisplayForCreator. Response shape (DisputeListItemResponse)
   * matches CreatorDisputeRow field-for-field, so no client-side mapping is needed. Mock: demo rows.
   */
  list: (): Promise<CreatorDisputeRow[]> =>
    isLive()
      ? http.request<CreatorDisputeRow[]>('GET', '/creator/disputes', { role: 'creator' })
      : mockOr(mockDisputeRows('creator')),

  /**
   * GET /creator/disputes/eligible-deals (CreatorDisputeController → DealService
   * .listEligibleForDispute, CR-80) — eligible = funded escrow, not already disputed/completed/
   * cancelled, computed server-side. Previously fetched the creator's ENTIRE deal history via
   * `deals.list('creator', 'all')` and applied this same filter client-side, which scaled poorly
   * for creators with a long deal history.
   */
  listEligibleDeals: (): Promise<Deal[]> =>
    isLive()
      ? http.request<Deal[]>('GET', '/creator/disputes/eligible-deals', { role: 'creator' })
      : mockOr(mockEligibleDeals),

  /** POST /deals/:dealId/disputes (DealController.java:130) — either party may open. */
  open: (dealId: string, reason: string) =>
    isLive()
      ? http.request<{ id: string }>('POST', `/deals/${dealId}/disputes`, {
          role: 'creator', body: { reason },
        })
      : mockOr<{ id: string }>({ id: 'dsp_new' }),
};

export const brandDisputes = {
  /**
   * GET /brand/disputes/list (BrandDisputeController.java:38, P2-14) — real dispute list
   * with display fields (campaign name, creator name, deal value), scoped to the brand's
   * workspace server-side via DisputeService#listDisplayForBrand. Response shape
   * (DisputeListItemResponse) matches BrandDisputeRow field-for-field, so no client-side
   * mapping is needed. Mock: demo rows.
   *
   * Opening a dispute is intentionally NOT wired here: this page is read-only by design
   * (see its header comment) — disputes are opened from the deal room via the shared
   * POST /deals/:dealId/disputes ("either party may open", DealController.java:167), and
   * BrandDisputeController exposes no separate open/raise-dispute endpoint of its own.
   */
  list: (): Promise<BrandDisputeRow[]> =>
    isLive()
      ? http.request<BrandDisputeRow[]>('GET', '/brand/disputes/list', { role: 'brand' })
      : mockOr(mockDisputeRows('brand')),
};

// ---------------------------------------------------------------------------
// Trend-Spark AI — soft nudge card (T4 backend: TrendSparkController.java,
// T7 frontend consumer). Shapes mirror TrendSparkDtos.java exactly — field
// names are the Java record component names (Jackson default camelCase).
// ---------------------------------------------------------------------------

/** Mirrors `TrendSparkDtos.NudgeResponse.mode` — anti-spam gate (spec §5b). */
export type TrendSparkNudgeMode = 'SNAPSBY' | 'OWN_CONTENT';

/** Mirrors `TrendSparkDtos.VideoCard`. Only present when `mode === 'SNAPSBY'`. */
export interface TrendSparkVideoCard {
  videoId: string;
  title: string;
  previewUrl: string;
  priceInr: number;
}

/**
 * How the trend behind this nudge was tagged. `KEYWORD` = the deterministic n8n
 * theme-tagger matched it directly; `AI_RECOVERED` = the deterministic tagger
 * found no match and the LLM Recovery Tagger (POST /internal/trendspark/tag)
 * recovered it onto the closed vocabulary. Optional + backward-compatible: older
 * backends omit it, in which case no provenance is shown (treated as KEYWORD).
 */
export type TrendSparkThemeSource = 'KEYWORD' | 'AI_RECOVERED';

/** Mirrors `TrendSparkDtos.NudgeResponse` (TrendSparkController.java:41). */
export interface TrendSparkNudge {
  nudgeId: string;
  mode: TrendSparkNudgeMode;
  campaignType: string;
  trendText: string;
  message: string;
  messageSource: 'AI' | 'FALLBACK';
  videos: TrendSparkVideoCard[];
  /** Optional trend-tag provenance (see TrendSparkThemeSource). Absent on older backends. */
  themeSource?: TrendSparkThemeSource;
}

/** Mock-mode-only sample so the card is visible in local/demo builds. Never used when
 *  `VITE_API_MODE=live` — live mode maps the real 204/200 response 1:1. */
const MOCK_TRENDSPARK_NUDGE: TrendSparkNudge = {
  nudgeId: 'nudge_mock_1',
  mode: 'OWN_CONTENT',
  campaignType: 'SEASONAL',
  trendText: 'Monsoon comfort cravings are trending this week',
  message:
    "Monsoon cravings are trending this week — same cosy energy your brand sells. Your last reel fits this moment perfectly.",
  messageSource: 'FALLBACK',
  videos: [],
};

export const trendspark = {
  /**
   * GET /brand/trendspark/nudge (TrendSparkController.java:40-47). Returns the current
   * nudge, or `null` when the backend has nothing to say — HTTP 204, below score
   * threshold, no active trend, or brand has no profile yet. This silence is the
   * anti-spam feature (spec §5b), not an error — callers must render nothing, never a
   * fabricated fallback nudge.
   */
  getNudge: (): Promise<TrendSparkNudge | null> =>
    isLive()
      ? http.requestOrNull<TrendSparkNudge>('GET', '/brand/trendspark/nudge')
      : mockOr<TrendSparkNudge | null>(MOCK_TRENDSPARK_NUDGE),

  /** POST /brand/trendspark/nudge/:id/click — stamps `clicked_at` (flywheel logging). */
  postNudgeClick: (nudgeId: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/brand/trendspark/nudge/${nudgeId}/click`)
      : mockOr(undefined),

  /** POST /brand/trendspark/nudge/:id/purchase — stamps `purchased_at`/`purchased_video_id`. */
  postNudgePurchase: (nudgeId: string, videoId?: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/brand/trendspark/nudge/${nudgeId}/purchase`, {
          body: { videoId: videoId ?? null },
        })
      : mockOr(undefined),
};

// ---------------------------------------------------------------------------
// Creator AI Co-pilot Tier-1 — daily suggestion (creator-copilot-API-CONTRACT.md, FROZEN v1)
// ---------------------------------------------------------------------------

/** Mirrors GET .../suggestion/today's `suggestion` object, API-CONTRACT.md §2. */
export interface DailySuggestion {
  id: string;
  theme: string;
  headline: string;
  contentIdea: string;
  expiresAt: string; // ISO 8601
}

/** Wire-level status from the backend (API-CONTRACT.md §2). Distinct from the UI-facing
 *  `SuggestionStatus` union derived from it in `useDailySuggestion` — don't conflate the two. */
export type CreatorCopilotWireStatus = 'pending_tagging' | 'ready' | 'no_suggestion_today';

export interface CreatorSuggestionTodayResponse {
  suggestion: DailySuggestion | null;
  status: CreatorCopilotWireStatus;
}

const MOCK_CREATOR_SUGGESTION: CreatorSuggestionTodayResponse = {
  status: 'ready',
  suggestion: {
    id: 'cc_mock_1',
    theme: 'skincare + winter',
    headline: 'Your skincare + winter niche is trending',
    contentIdea:
      'A 3-beat reel: morning routine cold-open, ingredient close-up, "why winter skin needs this" voiceover.',
    expiresAt: new Date(Date.now() + 24 * 3600 * 1000).toISOString(),
  },
};

export const creatorCopilot = {
  /**
   * GET /creator/copilot/suggestion/today (API-CONTRACT.md §1.1). Never throws for the
   * "nothing to say today" case — that's `status: 'pending_tagging' | 'no_suggestion_today'`
   * with `suggestion: null`, a 200 success, not a 4xx/204.
   */
  getTodaySuggestion: (): Promise<CreatorSuggestionTodayResponse> =>
    isLive()
      ? http.request<CreatorSuggestionTodayResponse>('GET', '/creator/copilot/suggestion/today', { role: 'creator' })
      : mockOr<CreatorSuggestionTodayResponse>(MOCK_CREATOR_SUGGESTION),

  /** POST /creator/copilot/suggestion/:id/dismiss (API-CONTRACT.md §1.2) — stamps `dismissed_at`. */
  dismissSuggestion: (id: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/creator/copilot/suggestion/${id}/dismiss`, { role: 'creator' })
      : mockOr(undefined),

  /** POST /creator/copilot/suggestion/:id/acted (API-CONTRACT.md §1.3) — stamps `acted_at`. */
  markSuggestionActed: (id: string): Promise<void> =>
    isLive()
      ? http.request<void>('POST', `/creator/copilot/suggestion/${id}/acted`, { role: 'creator' })
      : mockOr(undefined),
};

// ---------------------------------------------------------------------------
// Client crash reporting (CR-11)
// ---------------------------------------------------------------------------
// wiki/tech/cr-11-client-error-contract.md — LOCKED contract, do not deviate from the
// payload shape or the frontend rules documented there without Priya's sign-off.

/** Raw fields `ErrorBoundary.componentDidCatch` has on hand; `report` fills in the rest. */
export interface ClientErrorReportInput {
  message: string;
  stack: string | null;
  componentStack: string | null;
  /** `location.pathname` ONLY — never `search`/`hash`/`href` (contract: query strings here
   *  carry `?deal=<id>` and OAuth callback params). Caller's responsibility to pass the
   *  right value; this module does not read `window.location` itself. */
  pathname: string;
}

/** Client-side caps from the contract table. A courtesy only — "the server re-truncates
 *  everything" regardless of what arrives, so these exist to keep the request body small,
 *  not to enforce the contract. */
const CLIENT_ERROR_CAPS = {
  message: 500,
  stack: 4000,
  componentStack: 4000,
  pathname: 200,
  buildId: 64,
  userAgent: 300,
} as const;

function capString(value: string, max: number): string {
  return value.length > max ? value.slice(0, max) : value;
}

function capNullableString(value: string | null, max: number): string | null {
  return value == null ? null : capString(value, max);
}

export const clientErrors = {
  /**
   * POST /client-errors — reports an uncaught render crash. Response is `202 Accepted`
   * with an empty body (contract), so this deliberately does NOT go through `HttpClient`:
   * `http.request` expects the standard `{ success, data, error }` envelope and would
   * throw trying to parse an empty 202 body as JSON, which is exactly backwards for an
   * endpoint whose entire job is to survive being unreachable.
   *
   * - Mock mode never touches the network — there is no mock backend for this endpoint
   *   and this call site (a render crash) is the last place that should ever be blocked
   *   on one existing.
   * - Auth is optional server-side (contract). Attaches whichever role token happens to
   *   be in `localStorage`, brand checked first: a render crash can happen logged out
   *   entirely (the public portfolio page), or under either role, and this call site has
   *   no other way to know which.
   * - NEVER throws or rejects, by construction: mock-mode returns before touching
   *   anything that could fail, and the live-mode path wraps `fetch` in try/catch and
   *   resolves either way. `ErrorBoundary.componentDidCatch` (CR-11 rule 1) cannot survive
   *   a throw from its own error-reporting side effect, so this guarantee is load-bearing,
   *   not incidental — see ErrorBoundary.tsx's own belt-and-braces `.catch()` on the call.
   */
  report: (input: ClientErrorReportInput): Promise<void> => {
    if (!isLive()) return Promise.resolve();
    return (async () => {
      try {
        const token =
          localStorage.getItem(TOKEN_KEYS.brand) ?? localStorage.getItem(TOKEN_KEYS.creator);
        const body = {
          message: capString(input.message, CLIENT_ERROR_CAPS.message),
          stack: capNullableString(input.stack, CLIENT_ERROR_CAPS.stack),
          componentStack: capNullableString(input.componentStack, CLIENT_ERROR_CAPS.componentStack),
          pathname: capString(input.pathname, CLIENT_ERROR_CAPS.pathname),
          buildId: capString(APP_BUILD_ID, CLIENT_ERROR_CAPS.buildId),
          userAgent: capString(
            typeof navigator !== 'undefined' ? navigator.userAgent : '',
            CLIENT_ERROR_CAPS.userAgent,
          ),
        };
        await fetch(`${API_BASE_URL}/client-errors`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify(body),
        });
      } catch {
        // Rule 5 (contract) — failure is silent. A crash reporter that surfaces its own
        // errors to the user is worse than no crash reporter.
      }
    })();
  },
};

// ---------------------------------------------------------------------------
// Default export — single facade
// ---------------------------------------------------------------------------

export const api = {
  auth,
  workspaces,
  workspaceMembers,
  onboarding,
  campaigns,
  campaignTemplates,
  creators,
  deals,
  messages,
  shipments,
  contracts,
  deliverables,
  config,
  wallet,
  creatorProfile,
  me,
  payments,
  dashboard,
  notifications,
  billing,
  reports,
  uploads,
  portfolio,
  analytics,
  creatorAnalytics,
  contentPerformance,
  campaignTracking,
  storeIntegrations,
  creatorReviews,
  brandReviews,
  metaOAuth,
  creatorCoupons,
  affiliateEarnings,
  creatorCampaigns,
  creatorApplications,
  creatorDeliverables,
  creatorDisputes,
  brandDisputes,
  trendspark,
  creatorCopilot,
  clientErrors,
};

export default api;
