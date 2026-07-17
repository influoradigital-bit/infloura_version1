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
  CreatorMetrics,
  CreatorProfile,
  CreatorScores,
  DeliverableStatus,
  Platform,
} from './types';
import {
  persistBrandSession,
  type BackendTokenPair,
  type BrandSession,
} from './auth-session';

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

export interface ApiEnvelope<T> {
  success: boolean;
  data?: T;
  error?: { code: string; message: string };
  meta?: { page?: number; limit?: number; total?: number; hasMore?: boolean };
}

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ---------------------------------------------------------------------------
// Low-level HTTP client
// ---------------------------------------------------------------------------

class HttpClient {
  private getToken(role: Role = 'brand'): string | null {
    return localStorage.getItem(TOKEN_KEYS[role]);
  }

  setToken(role: Role, token: string): void {
    localStorage.setItem(TOKEN_KEYS[role], token);
  }

  clearToken(role: Role): void {
    localStorage.removeItem(TOKEN_KEYS[role]);
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

    const res = await fetch(url.toString(), {
      method,
      headers,
      // Kabir A1 — send/receive the HttpOnly refresh cookie. The refresh token is never in JS;
      // it rides only in this cookie (CORS allowCredentials is enabled server-side).
      credentials: 'include',
      body: body ? JSON.stringify(body) : undefined,
    });

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
      );
    }

    return envelope.data as T;
  }

  async upload<T>(path: string, file: File, role: Role = 'brand'): Promise<T> {
    const formData = new FormData();
    formData.append('file', file);
    const token = this.getToken(role);
    const res = await fetch(`${API_BASE_URL}${path}`, {
      method: 'POST',
      body: formData,
      credentials: 'include',
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    const envelope = (await res.json()) as ApiEnvelope<T>;
    if (!res.ok || !envelope.success) {
      throw new ApiError(envelope.error?.code || 'UPLOAD_FAILED', envelope.error?.message || 'Upload failed');
    }
    return envelope.data as T;
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

  /** POST /auth/creator/login */
  creatorLogin: (payload: LoginPayload) => {
    if (isLive()) {
      return http.request<LoginResponse>('POST', '/auth/creator/login', { body: payload, role: 'creator' });
    }
    assertMockAuthAllowed();
    return mockOr({ token: 'mock_creator_token', userId: 'cr_1', onboardingComplete: true });
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
  };
}

function campaignToPayload(payload: Partial<Campaign>) {
  const timeline = payload.timeline as { startDate?: Date | string; endDate?: Date | string } | undefined;
  const fmt = (d?: Date | string) => {
    if (!d) return undefined;
    const date = d instanceof Date ? d : new Date(d);
    return date.toISOString().slice(0, 10);
  };
  return {
    title: payload.title,
    description: payload.description,
    objectives: payload.objectives,
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
};

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

export const creators = {
  /** GET /creators?... */
  search: async (params: CreatorSearchParams = {}) => {
    if (!isLive()) return mockOr<CreatorProfile[]>([]);
    const rows = await http.request<CreatorProfile[]>('GET', '/creators', {
      query: creatorSearchQuery(params),
    });
    return rows.map(mapCreatorFromApi);
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
};

// ---------------------------------------------------------------------------
// Contracts
// ---------------------------------------------------------------------------

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

export const deliverables = {
  /** GET /deals/:dealId/deliverables */
  list: (role: Role, dealId: string) =>
    isLive()
      ? http.request('GET', `/deals/${dealId}/deliverables`, { role })
      : mockOr([]),

  /** POST /deliverables/:id/submit  (creator) */
  submit: (id: string, payload: { fileUrls: string[]; notes?: string }) =>
    isLive()
      ? http.request<{ status: DeliverableStatus }>('POST', `/deliverables/${id}/submit`, {
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

export const wallet = {
  /** GET /wallet */
  get: (role: Role) =>
    isLive()
      ? http.request<{
          availableBalance: number;
          escrowLocked: number;
          pendingPayouts: number;
          runwayDays?: number;
        }>('GET', '/wallet', { role })
      : mockOr({
          availableBalance: 285000,
          escrowLocked: 450000,
          pendingPayouts: 75000,
          runwayDays: 47,
        }),

  /** POST /wallet/recharge  (brand) */
  recharge: (amount: number, paymentMethod: 'upi' | 'card' | 'netbanking') =>
    isLive()
      ? http.request<{ transactionId: string; status: string }>('POST', '/wallet/recharge', {
          body: { amount, paymentMethod },
        })
      : mockOr({ transactionId: 'tx_new', status: 'PENDING' }),

  /** POST /wallet/withdraw  (creator) */
  withdraw: (amount: number) =>
    isLive()
      ? http.request<{ payoutId: string }>('POST', '/wallet/withdraw', {
          role: 'creator',
          body: { amount },
        })
      : mockOr({ payoutId: 'po_new' }),

  /** GET /wallet/transactions */
  transactions: (role: Role, page = 1, limit = 20) =>
    isLive()
      ? http.request('GET', '/wallet/transactions', { role, query: { page, limit } })
      : mockOr([]),
};

export const payments = {
  /** POST /deals/:dealId/escrow/fund  — brand funds escrow before delivery */
  fundEscrow: (dealId: string) =>
    isLive()
      ? http.request<{ status: string }>('POST', `/deals/${dealId}/escrow/fund`)
      : mockOr({ status: 'FUNDED' }),

  /** POST /deals/:dealId/payout/release — brand releases payment to creator */
  releasePayout: (dealId: string) =>
    isLive()
      ? http.request<{ payoutId: string; status: string }>('POST', `/deals/${dealId}/payout/release`)
      : mockOr({ payoutId: 'po_new', status: 'INITIATED' }),
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

export const notifications = {
  /** GET /notifications */
  list: (role: Role) =>
    isLive()
      ? http.request('GET', '/notifications', { role })
      : mockOr([]),

  /** POST /notifications/read-all */
  markAllRead: (role: Role) =>
    isLive()
      ? http.request<{ ok: true }>('POST', '/notifications/read-all', { role })
      : mockOr({ ok: true as const }),
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

  /** Media kit PDF — returns a direct download URL (server-rendered, watermarked) */
  mediaKitUrl: (username: string) =>
    `${API_BASE_URL}/portfolio/${encodeURIComponent(username)}/media-kit.pdf`,

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

// ---------------------------------------------------------------------------
// Campaign tracking — UTM links + coupon codes (Phase 4)
// GET/POST /campaigns/{campaignId}/tracking-links  (UtmCampaign entity, V23)
// GET/POST /campaigns/{campaignId}/coupons         (CouponCode entity, V24)
// Shapes are derived from the influora-api entities (UtmCampaign / CouponCode)
// and the fields the tracking UI actually reads — no fabricated per-coupon or
// per-link stats beyond the counters those rows carry.
// ---------------------------------------------------------------------------

/** One coupon code issued to a creator on a campaign (CouponCode entity). */
export interface CouponResponse {
  id: string;
  campaignId: string;
  creatorProfileId: string;
  code: string;
  discountType: 'percentage' | 'fixed';
  discountValue: number;
  usageLimit?: number | null;
  usageCount: number;
  expiresAt?: string | null; // ISO
  createdAt?: string; // ISO
}

/** Body for POST /campaigns/{campaignId}/coupons — the code itself is minted server-side. */
export interface CreateCouponPayload {
  creatorProfileId: string;
  discountType: 'percentage' | 'fixed';
  discountValue: number;
  usageLimit?: number;
  expiresAt?: string; // ISO
}

/** One UTM tracking link for a creator on a campaign (UtmCampaign entity). */
export interface TrackingLinkResponse {
  id: string;
  campaignId: string;
  collaborationId: string;
  creatorProfileId: string;
  baseUrl: string;
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
  utmContent?: string | null;
  utmTerm?: string | null;
  fullTrackingUrl: string;
  shortUrl?: string | null;
  clickCount: number;
  uniqueVisitors: number;
  conversionCount: number;
  revenueAttributed: number;
  createdAt?: string; // ISO
  updatedAt?: string; // ISO
  expiresAt?: string | null; // ISO
}

/** Body for POST /campaigns/{campaignId}/tracking-links — UTM params are derived server-side. */
export interface CreateTrackingLinkPayload {
  collaborationId: string;
  creatorProfileId: string;
  baseUrl: string;
  platform: string;
}

export const campaignTracking = {
  /** GET /campaigns/:campaignId/coupons */
  listCoupons: (campaignId: string) =>
    isLive()
      ? http.request<CouponResponse[]>('GET', `/campaigns/${campaignId}/coupons`)
      : mockOr<CouponResponse[]>([]),

  /** POST /campaigns/:campaignId/coupons */
  createCoupon: (campaignId: string, payload: CreateCouponPayload) =>
    isLive()
      ? http.request<CouponResponse>('POST', `/campaigns/${campaignId}/coupons`, { body: payload })
      : mockOr<CouponResponse>({
          id: `cpn_${Date.now()}`,
          campaignId,
          creatorProfileId: payload.creatorProfileId,
          code: `SAVE${Math.round(payload.discountValue)}`,
          discountType: payload.discountType,
          discountValue: payload.discountValue,
          usageLimit: payload.usageLimit ?? null,
          usageCount: 0,
          expiresAt: payload.expiresAt ?? null,
          createdAt: new Date().toISOString(),
        }),

  /** GET /campaigns/:campaignId/tracking-links */
  listTrackingLinks: (campaignId: string) =>
    isLive()
      ? http.request<TrackingLinkResponse[]>('GET', `/campaigns/${campaignId}/tracking-links`)
      : mockOr<TrackingLinkResponse[]>([]),

  /** POST /campaigns/:campaignId/tracking-links */
  createTrackingLink: (campaignId: string, payload: CreateTrackingLinkPayload) => {
    if (isLive()) {
      return http.request<TrackingLinkResponse>('POST', `/campaigns/${campaignId}/tracking-links`, {
        body: payload,
      });
    }
    const utmMedium = payload.platform.toLowerCase();
    const utmCampaign = `campaign_${campaignId}`;
    const sep = payload.baseUrl.includes('?') ? '&' : '?';
    const fullTrackingUrl = `${payload.baseUrl}${sep}utm_source=influora&utm_medium=${utmMedium}&utm_campaign=${utmCampaign}`;
    return mockOr<TrackingLinkResponse>({
      id: `utm_${Date.now()}`,
      campaignId,
      collaborationId: payload.collaborationId,
      creatorProfileId: payload.creatorProfileId,
      baseUrl: payload.baseUrl,
      utmSource: 'influora',
      utmMedium,
      utmCampaign,
      utmContent: null,
      utmTerm: null,
      fullTrackingUrl,
      shortUrl: null,
      clickCount: 0,
      uniqueVisitors: 0,
      conversionCount: 0,
      revenueAttributed: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      expiresAt: null,
    });
  },
};

// ---------------------------------------------------------------------------
// Creator-facing coupons ("My Coupons")
// ---------------------------------------------------------------------------

/**
 * A coupon as seen by the creator it was issued to. Carries only what the
 * backend actually tracks per coupon (code, discount, usage, expiry) plus the
 * campaign/brand names for display and an optional tracking link — no
 * fabricated per-coupon revenue/commission stats.
 */
export interface CreatorCouponResponse {
  id: string;
  campaignName: string;
  brandName: string;
  code: string;
  discountType: 'percentage' | 'fixed';
  discountValue: number;
  usageCount: number;
  usageLimit?: number | null;
  expiresAt?: string | null; // ISO
  trackingUrl?: string | null;
}

export const creatorCoupons = {
  /**
   * GET /creator/coupons — NOT YET BUILT on the backend. No creator-authed
   * endpoint lists a creator's coupons across all campaigns yet (brands can
   * generate coupons via CampaignTrackingController, but there is no creator
   * read path). In live mode this rejects with a NOT_IMPLEMENTED ApiError so
   * the UI can show an explicit "API not yet available" banner instead of a
   * silent empty state; in mock mode it returns clearly-illustrative rows so
   * the shell can be reviewed early.
   */
  listMine: (): Promise<CreatorCouponResponse[]> => {
    if (isLive()) {
      return Promise.reject(
        new ApiError('NOT_IMPLEMENTED', 'GET /creator/coupons is not available yet.'),
      );
    }
    return mockOr<CreatorCouponResponse[]>([
      {
        id: 'cpn_demo_1',
        campaignName: 'Summer Collection (demo)',
        brandName: 'Nykaa Fashion',
        code: 'PRIYA15',
        discountType: 'percentage',
        discountValue: 15,
        usageCount: 42,
        usageLimit: 100,
        expiresAt: null,
        trackingUrl: 'https://nykaafashion.com/summer?utm_source=influora&utm_medium=instagram',
      },
      {
        id: 'cpn_demo_2',
        campaignName: 'Skincare Series (demo)',
        brandName: 'Mamaearth',
        code: 'PRIYA200',
        discountType: 'fixed',
        discountValue: 200,
        usageCount: 18,
        usageLimit: null,
        expiresAt: null,
        trackingUrl: null,
      },
    ]);
  },
};

// ---------------------------------------------------------------------------
// Creator analytics (brand-facing) — AnalyticsController
// GET /analytics/creators/{creatorId}/metrics?startDate=&endDate=
// GET /analytics/creators/{creatorId}/scores
// ---------------------------------------------------------------------------

export const analytics = {
  /** GET /analytics/creators/:creatorId/metrics — trendData only populated when a date range is passed. */
  getCreatorMetrics: (creatorId: string, startDate?: string, endDate?: string) =>
    isLive()
      ? http.request<CreatorMetrics>('GET', `/analytics/creators/${creatorId}/metrics`, {
          query: { startDate, endDate },
        })
      : mockOr<CreatorMetrics>({
          totalReach: 0,
          totalImpressions: 0,
          totalEngagements: 0,
          engagementRate: 0,
          followerGrowth: 0,
          avgViewsPerPost: 0,
          trendData: [],
        }),

  /** GET /analytics/creators/:creatorId/scores — unbuilt score fields come back null, never faked. */
  getCreatorScores: (creatorId: string) =>
    isLive()
      ? http.request<CreatorScores>('GET', `/analytics/creators/${creatorId}/scores`)
      : mockOr<CreatorScores>({
          authenticityScore: null,
          fakeFollowerReasons: null,
          qualityScore: null,
          engagementConsistency: null,
          postingFrequency: null,
          audienceMatchScore: null,
          brandSafetyScore: null,
          garmFlags: null,
          contentSentiment: null,
          estimatedRateMin: null,
          estimatedRateMax: null,
          rateCurrency: null,
          rateConfidence: null,
          algorithmVersion: null,
          computedAt: null,
        }),
};

// ---------------------------------------------------------------------------
// Meta (Instagram/Facebook) OAuth — MetaOAuthController / MetaDtos
// GET /meta/oauth/authorize  → { authorizationUrl, state }
// GET /meta/oauth/callback   → { connected, grantedScopes }
// The callback endpoint returns JSON (not a 302); the frontend calls it itself
// from the redirect landing route. Connection state is cached in localStorage
// so settings can render the connected/scopes UI without a round-trip.
// ---------------------------------------------------------------------------

const META_CONNECTION_KEY = 'meta_connection';

export interface MetaConnectionState {
  connected: boolean;
  scopes: string[];
}

export interface MetaAuthorizeResponse {
  authorizationUrl: string;
  state: string;
}

export interface MetaCallbackResponse {
  connected: boolean;
  grantedScopes: string[];
}

export const metaOAuth = {
  /** GET /meta/oauth/authorize — returns the Meta OAuth dialog URL to redirect the browser to. */
  authorize: (): Promise<MetaAuthorizeResponse> =>
    isLive()
      ? http.request<MetaAuthorizeResponse>('GET', '/meta/oauth/authorize', { role: 'creator' })
      : mockOr<MetaAuthorizeResponse>({
          authorizationUrl: `${window.location.origin}/creator/settings/meta/callback?code=mock_code&state=mock_state`,
          state: 'mock_state',
        }),

  /** GET /meta/oauth/callback — exchanges the code and reports the granted scopes. */
  callback: (code: string, state: string): Promise<MetaCallbackResponse> =>
    isLive()
      ? http.request<MetaCallbackResponse>('GET', '/meta/oauth/callback', {
          role: 'creator',
          query: { code, state },
        })
      : mockOr<MetaCallbackResponse>({
          connected: true,
          grantedScopes: [
            'instagram_basic',
            'instagram_manage_insights',
            'pages_show_list',
            'pages_read_engagement',
          ],
        }),

  /** Reads the cached Meta connection state from localStorage (never throws). */
  getLocalConnectionState: (): MetaConnectionState => {
    try {
      const raw = localStorage.getItem(META_CONNECTION_KEY);
      if (!raw) return { connected: false, scopes: [] };
      const parsed = JSON.parse(raw) as Partial<MetaConnectionState>;
      return {
        connected: Boolean(parsed.connected),
        scopes: Array.isArray(parsed.scopes) ? parsed.scopes : [],
      };
    } catch {
      return { connected: false, scopes: [] };
    }
  },

  /** Persists the Meta connection state to localStorage after a successful callback. */
  setLocalConnectionState: (connected: boolean, scopes: string[]): void => {
    localStorage.setItem(META_CONNECTION_KEY, JSON.stringify({ connected, scopes }));
  },
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
  wallet,
  payments,
  dashboard,
  notifications,
  uploads,
  portfolio,
  campaignTracking,
  creatorCoupons,
  analytics,
  metaOAuth,
};

export default api;
