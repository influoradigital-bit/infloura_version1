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
  DeliverableStatus,
  Platform,
  VerificationStatus,
} from './types';
import {
  persistBrandSession,
  type BackendTokenPair,
  type BrandSession,
} from './auth-session';

// Re-export types needed by consumers
export type { DeliverableStatus } from './types';

// ---------------------------------------------------------------------------
// Environment / config
// ---------------------------------------------------------------------------

const API_BASE_URL =
  (import.meta as any).env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

const API_MODE: 'live' | 'mock' =
  (import.meta as any).env?.VITE_API_MODE === 'live' ? 'live' : 'mock';

/** True when `VITE_API_MODE=live` */
export function isApiLive(): boolean {
  return API_MODE === 'live';
}

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

class HttpClient {
  /** Dedupes concurrent 401s for the same role into a single `/auth/refresh` call (H-19). */
  private refreshPromises: Partial<Record<Role, Promise<string | null>>> = {};

  private getToken(role: Role = 'brand'): string | null {
    return localStorage.getItem(TOKEN_KEYS[role]);
  }

  setToken(role: Role, token: string): void {
    localStorage.setItem(TOKEN_KEYS[role], token);
  }

  clearToken(role: Role): void {
    localStorage.removeItem(TOKEN_KEYS[role]);
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
   * Runs `fetch`; on a 401 (and only once per call) attempts a refresh + retry with the
   * new token. If refresh fails, clears the stale token and returns the original 401
   * response so the caller's normal envelope-error handling takes over.
   */
  private async fetchWithAuthRetry(
    url: string,
    init: RequestInit,
    role: Role,
    hasAuthHeader: boolean,
    retried = false,
  ): Promise<Response> {
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

    let envelope: ApiEnvelope<T>;
    try {
      envelope = (await res.json()) as ApiEnvelope<T>;
    } catch {
      throw new ApiError('NETWORK_ERROR', `Invalid JSON from ${path}`, res.status);
    }

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
    let envelope: ApiEnvelope<T>;
    try {
      envelope = (await res.json()) as ApiEnvelope<T>;
    } catch {
      throw new ApiError('NETWORK_ERROR', `Invalid JSON from ${path}`, res.status);
    }
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
    let envelope: ApiEnvelope<T>;
    try {
      envelope = (await res.json()) as ApiEnvelope<T>;
    } catch {
      throw new ApiError('NETWORK_ERROR', `Invalid JSON from ${path}`, res.status);
    }
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
export interface LoginResponse { token: string; userId: string; onboardingComplete: boolean }

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
   * login. Creator has no `persistCreatorSession` helper (unlike brand's
   * `persistBrandSession`); the caller stores the raw token via
   * `api.auth.setToken('creator', result.token)`.
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
    return {
      token: data.accessToken,
      userId: data.user.id,
      onboardingComplete: data.onboardingCompleted ?? false,
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
    return {
      token: data.accessToken,
      userId: data.user.id,
      onboardingComplete: data.onboardingCompleted ?? false,
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

  /** POST /auth/forgot-password */
  forgotPassword: async (email: string) => {
    if (!isLive()) return mockOr({ sent: true });
    await http.request<{ message: string }>('POST', '/auth/forgot-password', { body: { email } });
    return { sent: true };
  },

  /** POST /auth/logout */
  logout: (role: Role) => {
    http.clearToken(role);
    if (isLive()) return http.request<{ message: string }>('POST', '/auth/logout', { role });
    return mockOr({ message: 'ok' });
  },

  setToken: (role: Role, token: string) => http.setToken(role, token),
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

  /**
   * POST /onboarding/creator/payout  — deferred to first withdrawal.
   */
  saveCreatorPayout: (payload:
    | { method: 'upi'; upiId: string }
    | { method: 'bank'; bankAccount: string; ifsc: string; accountName: string }
  ) =>
    isLive()
      ? http.request<{ payoutId: string }>('POST', '/onboarding/creator/payout', {
          body: payload,
          role: 'creator',
        })
      : mockOr({ payoutId: 'po_1' }),
};

// ---------------------------------------------------------------------------
// Campaigns
// ---------------------------------------------------------------------------

export interface CampaignListParams {
  status?: CampaignStatus | 'ALL';
  page?: number;
  limit?: number;
  search?: string;
}

type CampaignApiRow = Campaign & {
  collaboratorsCount?: number;
  activeCollaborations?: number;
  completedCollaborations?: number;
  totalSpend?: number;
};

function mapCampaignFromApi(row: CampaignApiRow): Campaign {
  const timeline = row.timeline as Campaign['timeline'];
  return {
    ...row,
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

  // FE CampaignType ('OPEN' | 'DIRECT' | 'HYPE' — src/lib/types.ts:15) does not
  // match the backend's CampaignIntentType enum (HYPE | DIRECT | REVIEW |
  // STANDARD). 'OPEN' has no backend equivalent and would 400, so only forward
  // campaignType when it's a value the backend actually accepts; omitting it
  // otherwise preserves today's behavior for non-Hype creates (backend
  // defaults absent/null campaignType to STANDARD — CampaignService.java:115-116).
  // Reconciling the full OPEN/STANDARD mismatch is a separate, pre-existing item.
  const campaignType = payload.campaignType !== 'OPEN' ? payload.campaignType : undefined;

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

export const campaigns = {
  /** GET /campaigns?status=&page=&limit=&search= */
  list: async (params: CampaignListParams = {}) => {
    if (!isLive()) return mockOr<Campaign[]>([]);
    const status =
      params.status && params.status !== 'ALL' ? String(params.status) : undefined;
    const rows = await http.request<CampaignApiRow[]>('GET', '/campaigns', {
      query: {
        page: params.page,
        limit: params.limit,
        search: params.search,
        status,
      },
    });
    return rows.map(mapCampaignFromApi);
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
  | 'completed';

export interface Deal {
  id: string;
  campaignId: string;
  campaignName: string;
  counterpartyId: string;     // creatorId for brand, brandId for creator
  counterpartyName: string;
  counterpartyAvatar?: string;
  counterpartyHandle?: string;
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
  list: (role: Role, status: DealStatusFilter = 'all') =>
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

  /** POST /deals/:id/counter */
  counter: (
    id: string,
    payload: { amount: number; message?: string; deliverables?: Array<{ type: string; qty: number }> },
    role: Role = 'creator',
  ) =>
    isLive()
      ? http.request<Deal>('POST', `/deals/${id}/counter`, { role, body: payload })
      : mockOr<{ id: string }>({ id }),

  /** POST /deals — brand creates a new proposal */
  create: (payload: {
    campaignId: string;
    creatorId: string;
    amount: number;
    deliverables: Array<{ type: string; qty: number }>;
    deadline: string;
    usageRights: string;
    exclusivity: boolean;
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
  metadata?: Record<string, any>;
  createdAt: string;
  readBy: string[];
}

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
   * Returns a handle whose `close()` aborts the underlying fetch — callers
   * must call it on deal switch and on unmount to avoid leaking connections
   * or letting a stale deal's stream write into the newly selected one.
   *
   * Never throws synchronously and never rejects the caller's render path:
   * connection failures, non-OK responses, and stream read errors all route
   * through `handlers.onError` (optional) so a dropped/failed stream degrades
   * silently to the existing fetch-on-load (`messages.list`) path.
   */
  stream: (role: Role, dealId: string, handlers: DealMessageStreamHandlers): DealMessageStreamHandle => {
    const controller = new AbortController();

    void (async () => {
      const token = localStorage.getItem(TOKEN_KEYS[role]);
      let response: Response;
      try {
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
        if (controller.signal.aborted) return;
        handlers.onError?.(
          err instanceof Error ? err : new Error('Deal message stream connection failed'),
        );
        return;
      }

      if (controller.signal.aborted) return;

      if (!response.ok || !response.body) {
        handlers.onError?.(new Error(`Deal message stream rejected (HTTP ${response.status})`));
        return;
      }

      handlers.onOpen?.();

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
        if (!controller.signal.aborted) {
          handlers.onError?.(err instanceof Error ? err : new Error('Deal message stream interrupted'));
        }
      }
    })();

    return {
      close: () => controller.abort(),
    };
  },
};

export interface DealMessageStreamHandlers {
  /** Called for each `deal-message` SSE event with the parsed DealMessage DTO. */
  onMessage: (message: DealMessage) => void;
  /** Called once the connection is established (headers received), before any events. */
  onOpen?: () => void;
  /**
   * Called on connection failure, a non-OK response, or a stream read error.
   * Never throws internally — the caller decides whether/how to degrade.
   */
  onError?: (error: Error) => void;
}

export interface DealMessageStreamHandle {
  /** Aborts the underlying fetch and stops reading the stream. */
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
// Contracts
// ---------------------------------------------------------------------------

/** Contract row shape (Task #23) consumed by src/lib/creator-contract-mappers.ts. */
export interface ContractApiRecord {
  id: string;
  dealId: string;
  status: ContractStatus;
  brandSignedAt: string | null;
  creatorSignedAt: string | null;
  createdAt?: string;
  pdfUrl?: string | null;
}

export const contracts = {
  /** GET /contracts?dealId= */
  list: (role: Role, dealId?: string) =>
    isLive()
      ? http.request('GET', '/contracts', { role, query: { dealId } })
      : mockOr([]),

  /** GET /contracts/:id */
  get: (role: Role, id: string) =>
    isLive()
      ? http.request('GET', `/contracts/${id}`, { role })
      : mockOr(null),

  /** POST /contracts — brand generates contract for a deal */
  generate: (dealId: string) =>
    isLive()
      ? http.request<{ contractId: string }>('POST', '/contracts', { body: { dealId } })
      : mockOr({ contractId: 'CTR_new' }),

  /** POST /contracts/:id/sign */
  sign: (role: Role, id: string, signature: { name: string; agreedAt: string }) =>
    isLive()
      ? http.request<{ status: ContractStatus }>('POST', `/contracts/${id}/sign`, {
          role,
          body: signature,
        })
      : mockOr({ status: 'ACTIVE' as ContractStatus }),
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
        }),

  /** POST /creator/deliverables/:id/submit  (CreatorDeliverableController.java:75 — creator-scoped, not a generic /deliverables route) */
  submit: (id: string, payload: { fileUrls: string[]; notes?: string }) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/creator/deliverables/${id}/submit`, {
          role: 'creator',
          body: payload,
        })
      : mockOr({ status: 'SUBMITTED' as DeliverableStatus }),

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

export const config = {
  /** GET /config/razorpay — source of the `key` param for `window.Razorpay(...)`. */
  razorpay: () =>
    isLive()
      ? http.request<RazorpayConfigResponse>('GET', '/config/razorpay')
      // Mock mode never talks to a real Razorpay account — this key is not live/usable.
      : mockOr<RazorpayConfigResponse>({ keyId: 'rzp_test_mock' }),
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

  /** POST /wallet/recharge  (brand) */
  recharge: (amount: number, paymentMethod: 'upi' | 'card' | 'netbanking') =>
    isLive()
      ? http.request<{ transactionId: string; status: string }>('POST', '/wallet/recharge', {
          body: { amount, paymentMethod },
        })
      : mockOr({ transactionId: 'tx_new', status: 'PENDING' }),

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

  /** GET /wallet/transactions — WalletController.java:122 (creator-scoped ledger, paginated). */
  transactions: (role: Role, page = 1, limit = 20) =>
    isLive()
      ? http.request<WalletTransactionRow[]>('GET', '/wallet/transactions', { role, query: { page, limit } })
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

export const notifications = {
  /** GET /notifications */
  list: (role: Role) =>
    isLive()
      ? http.request<NotificationItem[]>('GET', '/notifications', { role })
      : mockOr<NotificationItem[]>([]),

  /** POST /notifications/read — body: { notificationId } (NotificationController.java). */
  markRead: (role: Role, id: string) =>
    isLive()
      ? http.request<{ ok: true }>('POST', '/notifications/read', { role, body: { notificationId: id } })
      : mockOr({ ok: true as const }),

  /** POST /notifications/read-all */
  markAllRead: (role: Role) =>
    isLive()
      ? http.request<{ ok: true }>('POST', '/notifications/read-all', { role })
      : mockOr({ ok: true as const }),

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

  /** POST /billing/checkout — Phase 2 (Razorpay Subscriptions); backend stub throws NOT_YET_IMPLEMENTED today. */
  initiateCheckout: (planCode: 'FREE' | 'PRO') =>
    isLive()
      ? http.request<{ checkoutUrl: string }>('POST', '/billing/checkout', { body: { planCode } })
      : Promise.reject(new ApiError('NOT_YET_IMPLEMENTED', 'Razorpay checkout ships in Phase 2')),

  /** POST /billing/cancel — Phase 2; backend stub throws NOT_YET_IMPLEMENTED today. */
  cancelSubscription: () =>
    isLive()
      ? http.request<void>('POST', '/billing/cancel')
      : Promise.reject(new ApiError('NOT_YET_IMPLEMENTED', 'Cancellation ships in Phase 2')),
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
 * `CreatorProfile.gstin`/`.pan`/`.taxRegistrationStatus` (see `CreatorProfile#applyTaxIdentity`),
 * but there is **no HTTP endpoint** for a creator to submit it yet — `applyTaxIdentity` is only
 * called internally by `CampaignServiceInvoiceService` to auto-provision `creatorInvoiceCode` on
 * first Doc#2 issuance (Vikram's self-flagged note in SHARED_CONTEXT.md — no GST-onboarding flow
 * exists). `MeCreatorProfileController`'s `CreatorProfilePatchRequest` has no gstin/pan fields
 * either. Per TECH-STACK.md rule #7, this always rejects with a typed NOT_IMPLEMENTED rather than
 * fabricating a PATCH path — same discipline as `affiliateEarnings` before Wave D shipped.
 */
export interface CreatorTaxIdentitySubmission {
  gstin?: string;
  pan?: string;
}

export const creatorTaxIdentity = {
  submit: (_body: CreatorTaxIdentitySubmission) =>
    Promise.reject<void>(
      new ApiError(
        'NOT_IMPLEMENTED',
        'Tax-identity capture has no backend endpoint yet (D14-B — GST onboarding flow not built)',
      ),
    ),
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
  lastSyncedAt: string; // ISO
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
        verified: true,
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

const emptyMetrics: CreatorMetrics = {
  totalReach: 0, totalImpressions: 0, totalEngagements: 0,
  engagementRate: null, followerGrowth: 0, avgViewsPerPost: null, trendData: [],
};
const emptyScores: CreatorScores = {
  authenticityScore: null, fakeFollowerReasons: [], qualityScore: null,
  engagementConsistency: null, postingFrequency: null, audienceMatchScore: null,
  brandSafetyScore: null, garmFlags: null, contentSentiment: null,
  estimatedRateMin: null, estimatedRateMax: null, rateCurrency: null,
  rateConfidence: null, algorithmVersion: null, computedAt: null,
};
const emptyDemographics: CreatorDemographics = {
  hasData: false, ageGenderBreakdown: null, countryBreakdown: null,
  cityBreakdown: null, localeBreakdown: null, fetchedAt: null,
};

export const analytics = {
  /** GET /analytics/creators/:creatorId/metrics?startDate=&endDate= (AnalyticsController.java:58) */
  getCreatorMetrics: (creatorId: string, startDate?: string, endDate?: string) =>
    isLive()
      ? http.request<CreatorMetrics>('GET', `/analytics/creators/${creatorId}/metrics`, {
          query: { startDate, endDate },
        })
      : mockOr<CreatorMetrics>(emptyMetrics),

  /** GET /analytics/creators/:creatorId/scores (AnalyticsController.java:71) */
  getCreatorScores: (creatorId: string) =>
    isLive()
      ? http.request<CreatorScores>('GET', `/analytics/creators/${creatorId}/scores`)
      : mockOr<CreatorScores>(emptyScores),

  /** GET /analytics/creators/:creatorId/demographics (AnalyticsController.java). */
  getCreatorDemographics: (creatorId: string): Promise<CreatorDemographics> =>
    isLive()
      ? http.request<CreatorDemographics>('GET', `/analytics/creators/${creatorId}/demographics`)
      : mockOr<CreatorDemographics>(emptyDemographics),
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
      : mockOr<CreatorMetrics>(emptyMetrics),

  /** GET /creator/analytics/me/scores (CreatorAnalyticsController.java:46) */
  getMyScores: () =>
    isLive()
      ? http.request<CreatorScores>('GET', '/creator/analytics/me/scores', { role: 'creator' })
      : mockOr<CreatorScores>(emptyScores),

  /** GET /creator/analytics/me/demographics (CreatorAnalyticsController.java:52) */
  getMyDemographics: () =>
    isLive()
      ? http.request<CreatorDemographics>('GET', '/creator/analytics/me/demographics', { role: 'creator' })
      : mockOr<CreatorDemographics>(emptyDemographics),
};

// ---------------------------------------------------------------------------
// Content performance — AnalyticsController @ /analytics/creators/:id/media
// ---------------------------------------------------------------------------

/** Per-post row returned by GET /analytics/creators/:creatorId/media. */
export interface ContentPerformanceItem {
  mediaId: string;
  mediaType: string;
  postedAt: string;
  reach: number;
  impressions: number;
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
  /** GET /campaigns/:campaignId/tracking-links (CampaignTrackingController.java:74) */
  listTrackingLinks: async (campaignId: string): Promise<TrackingLinkResponse[]> => {
    if (!isLive()) return mockOr<TrackingLinkResponse[]>([]);
    const res = await http.request<{ trackingLinks: TrackingLinkResponse[] }>('GET', `/campaigns/${campaignId}/tracking-links`);
    return res.trackingLinks ?? [];
  },

  /** POST /campaigns/:campaignId/tracking-links (CampaignTrackingController.java:56) */
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

  /** GET /campaigns/:campaignId/coupons (CampaignTrackingController.java:102) */
  listCoupons: async (campaignId: string): Promise<CouponResponse[]> => {
    if (!isLive()) return mockOr<CouponResponse[]>([]);
    const res = await http.request<{ coupons: CouponResponse[] }>('GET', `/campaigns/${campaignId}/coupons`);
    return res.coupons ?? [];
  },

  /** POST /campaigns/:campaignId/coupons (CampaignTrackingController.java:83) */
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
};

// ---------------------------------------------------------------------------
// Meta OAuth — Instagram/Facebook connect (creator). Server-managed CSRF state.
// ---------------------------------------------------------------------------

export interface MetaAuthorizeResponse { authorizationUrl: string; state: string }
export interface MetaCallbackResponse {
  connected: boolean;
  grantedScopes: string[];
  /** 'personal' means OAuth succeeded but the linked IG is not a Business/Creator account —
   *  creator co-pilot cannot use it (`connected` is `false` in this case). Absent/undefined on
   *  the ordinary success path. Creator-copilot-API-CONTRACT.md §4.2. */
  accountType?: 'personal' | 'business';
}
export interface MetaConnectionState {
  connected: boolean;
  scopes: string[];
  /** null = unknown (never resolved a callback yet). Persisted alongside `connected`/`scopes` in
   *  the same localStorage mirror (`META_CONNECTION_KEY`). Creator-copilot-API-CONTRACT.md §4.2. */
  accountType: 'personal' | 'business' | null;
}

const META_CONNECTION_KEY = 'meta_connection';
/** Mirrors MetaOAuthService.REQUIRED_SCOPES (MetaOAuthService.java:28-33). */
const META_REQUIRED_SCOPES = ['instagram_basic', 'instagram_manage_insights', 'pages_show_list', 'pages_read_engagement'];

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

  getLocalConnectionState: (): MetaConnectionState => {
    try {
      const raw = localStorage.getItem(META_CONNECTION_KEY);
      if (!raw) return { connected: false, scopes: [], accountType: null };
      const p = JSON.parse(raw) as Partial<MetaConnectionState>;
      return { connected: !!p.connected, scopes: p.scopes ?? [], accountType: p.accountType ?? null };
    } catch {
      return { connected: false, scopes: [], accountType: null };
    }
  },

  /** `accountType` optional + defaults to `null` so existing call sites (pre-dating this field)
   *  keep compiling untouched; pass it explicitly once a caller has a callback's `accountType`. */
  setLocalConnectionState: (connected: boolean, scopes: string[], accountType: 'personal' | 'business' | null = null): void => {
    localStorage.setItem(META_CONNECTION_KEY, JSON.stringify({ connected, scopes, accountType }));
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
}

export interface CreatorCampaignBrandSummary { workspaceId: string; name: string; logoUrl?: string | null }
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
  /** GET /creator/affiliate-earnings (CreatorAffiliateEarningController.java:28) */
  get: () =>
    isLive()
      ? http.request<CreatorAffiliateEarningsResponse>('GET', '/creator/affiliate-earnings', { role: 'creator' })
      : mockOr<CreatorAffiliateEarningsResponse>({
          earnings: [],
          summary: { thisMonthSales: 0, thisMonthRevenue: 0, thisMonthCommission: 0, unsettledCommission: 0, currency: 'INR' },
        }),
};

export const creatorCampaigns = {
  /** GET /creator/campaigns (CreatorCampaignController.java:40) — data=items, envelope.meta=page info */
  browse: async (params: CreatorCampaignBrowseParams = {}) => {
    if (!isLive())
      return mockOr<{ campaigns: CreatorCampaignListItem[]; meta: { hasMore: boolean } }>({ campaigns: [], meta: { hasMore: false } });
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
      : mockOr<CreatorCampaignDetail | null>(null),

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

export const creatorDeliverables = {
  /** GET /creator/deliverables?collaboration_id= (CreatorDeliverableController.java:44) */
  listForDeal: (collaborationId: string) =>
    isLive()
      ? http.request<CreatorDeliverableListItem[]>('GET', '/creator/deliverables', {
          role: 'creator', query: { collaboration_id: collaborationId },
        })
      : mockOr<CreatorDeliverableListItem[]>([]),
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
 * Creator side: still derived from disputed deals (`Deal.status === 'DISPUTED'`)
 * via `disputedDealsAsRows`, so the dispute-detail fields stay undefined there
 * and the page shows its "partial data" banner — no join data is fabricated.
 * `GET /creator/disputes` (CreatorDisputeController) now exists with the same
 * enriched shape as the brand endpoint; wiring it is a follow-up, not done here.
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

function disputedDealsAsRows(role: Role): Promise<DisputeRow[]> {
  return Promise.resolve(deals.list(role, 'all')).then((rows) =>
    rows
      .filter((d) => d.status === 'DISPUTED')
      .map((d) => ({
        collaborationId: d.id,
        campaignName: d.campaignName,
        counterpartyName: d.counterpartyName,
        dealValue: d.dealValue,
        currency: d.currency,
      })),
  );
}

/** Demo fixtures (mock mode only) — live mode derives partial rows from disputed deals. */
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
  /** Live: derived from /deals (partial, no GET /creator/disputes yet). Mock: demo rows. */
  list: (): Promise<CreatorDisputeRow[]> =>
    isLive() ? disputedDealsAsRows('creator') : mockOr(mockDisputeRows('creator')),

  /** Eligible = funded escrow, not already disputed/completed/cancelled. */
  listEligibleDeals: async (): Promise<Deal[]> => {
    if (!isLive()) return mockOr(mockEligibleDeals);
    const rows = await deals.list('creator', 'all');
    return rows.filter(
      (d) => d.escrowFunded && !['DISPUTED', 'COMPLETED', 'CANCELLED'].includes(d.status),
    );
  },

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
// Default export — single facade
// ---------------------------------------------------------------------------

export const api = {
  auth,
  workspaces,
  onboarding,
  campaigns,
  creators,
  deals,
  messages,
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
  creatorDeliverables,
  creatorDisputes,
  brandDisputes,
  trendspark,
  creatorCopilot,
};

export default api;
