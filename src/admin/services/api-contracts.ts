/**
 * INFLUORA ADMIN PANEL — API Contracts
 * Owner: Priya (CTO)
 * Reference: docs/ADMIN-PANEL-SPEC.md
 *
 * This file defines the API client for admin panel endpoints.
 * Backend implementation: Vikram
 */

import type {
  AdminLoginRequest,
  AdminLoginResponse,
  AdminUser,
  CeoPulseData,
  Brand,
  BrandDetail,
  BrandKycAction,
  BrandFilters,
  Creator,
  CreatorSummary,
  CreatorDetail,
  CreatorApplicationAction,
  CreatorTierAdjustment,
  CreatorFilters,
  CampaignSummary,
  CampaignDetail,
  CampaignFilters,
  RevenueSnapshot,
  EscrowSummary,
  PayoutQueueItem,
  ReconciliationItem,
  PlatformFeeConfigWire,
  PlatformFeeUpdateRequest,
  PlatformFeeHistoryEntryWire,
  SupportTicket,
  TicketDetail,
  TicketFilters,
  DisputeDetail,
  DisputeFilters,
  DisputeResolution,
  DisputeListResponse,
  ContentFlag,
  ApprovalWorkflow,
  AccountSuspension,
  ModerationAction,
  AuditLogEntry,
  ErrorLogEntry,
  EmailQueueItem,
  AcquisitionMetrics,
  GrowthMetrics,
  PlatformReputationScore,
  PaginatedResponse,
  ApiResponse,
  BillingMetrics,
  AdminSubscriptionRow,
  PaginatedSubscriptionResponse,
  CompSubscriptionRequest,
  OverrideSubscriptionRequest,
  AdminSubscriptionActionResult
} from '../types/admin.types';

// ============================================
// BASE CONFIG
// ============================================

// Backend runs with `server.servlet.context-path: /api/v1` (Vikram's AdminAuthController /
// AdminDashboardController mount at `/admin/**`, resolving to `/api/v1/admin/**`). This base MUST
// carry the `/v1` segment or every admin call 404s. See AdminAuthController class javadoc.
const API_BASE = '/api/v1/admin';

async function apiRequest<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<ApiResponse<T>> {
  const token = localStorage.getItem('admin_token');

  const response = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token && { Authorization: `Bearer ${token}` }),
      ...options.headers,
    },
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: 'Request failed' }));
    return { success: false, error: error.message };
  }

  let data: T;
  try {
    data = await response.json();
  } catch {
    return { success: false, error: 'Malformed response from server' };
  }
  return { success: true, data };
}

// ============================================
// AUTH APIs
// ============================================

export const authApi = {
  login: (credentials: AdminLoginRequest) =>
    apiRequest<AdminLoginResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    }),

  logout: () =>
    apiRequest<void>('/auth/logout', { method: 'POST' }),

  refreshToken: (refreshToken: string) =>
    apiRequest<{ token: string }>('/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    }),

  getCurrentUser: () =>
    apiRequest<AdminUser>('/auth/me'),

  setupMfa: () =>
    apiRequest<{ qrCode: string; secret: string }>('/auth/mfa/setup', { method: 'POST' }),

  verifyMfa: (code: string) =>
    apiRequest<void>('/auth/mfa/verify', {
      method: 'POST',
      body: JSON.stringify({ code }),
    }),
};

// ============================================
// DASHBOARD APIs
// ============================================

export const dashboardApi = {
  getPulse: () =>
    apiRequest<CeoPulseData>('/dashboard/pulse'),

  getFinancialSummary: (period: 'day' | 'week' | 'month' | 'quarter') =>
    apiRequest<RevenueSnapshot[]>(`/dashboard/financial?period=${period}`),

  getOperationsSummary: () =>
    apiRequest<{
      activeCampaigns: number;
      campaignsAtRisk: number;
      reviewBacklog: number;
      supportQueueDepth: number;
      avgReviewTime: number;
    }>('/dashboard/operations'),

  getMarketingSummary: () =>
    apiRequest<{
      acquisitionMetrics: AcquisitionMetrics;
      growthMetrics: GrowthMetrics;
      reputationScore: PlatformReputationScore;
    }>('/dashboard/marketing'),
};

// ============================================
// BRAND APIs
// ============================================

export const brandApi = {
  /**
   * GET /brands (Task #2, AdminBrandController) — paginated brand list for the admin Users page,
   * with server-side search (companyName/email) + kycStatus + isSuspended filters.
   *
   * Builds params manually rather than spreading `filters` straight into `URLSearchParams`
   * (the pattern used elsewhere in this file): once a filter is cleared back to `undefined`
   * (e.g. a Select reset to "All"), `new URLSearchParams({ kycStatus: undefined, ... })`
   * serializes it as the literal string `"kycStatus=undefined"` — not an absent param — which
   * the backend would read as a real (bogus) filter value instead of "no filter". Only
   * `search`/`kycStatus`/`isSuspended` are sent — `industry`/`size` have no backing query param
   * on `AdminBrandController.list()` yet, so they're intentionally omitted rather than sent as
   * dead params.
   */
  list: (filters: BrandFilters, page = 1, pageSize = 20) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (filters.search) params.set('search', filters.search);
    if (filters.kycStatus) params.set('kycStatus', filters.kycStatus);
    if (filters.isSuspended !== undefined) params.set('isSuspended', String(filters.isSuspended));
    return apiRequest<PaginatedResponse<Brand>>(`/brands?${params}`);
  },

  getById: (id: string, signal?: AbortSignal) =>
    apiRequest<BrandDetail>(`/brands/${id}`, { signal }),

  update: (id: string, data: Partial<Brand>) =>
    apiRequest<Brand>(`/brands/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  verifyKyc: (action: BrandKycAction) =>
    apiRequest<Brand>(`/brands/${action.brandId}/verify-kyc`, {
      method: 'POST',
      body: JSON.stringify(action),
    }),

  suspend: (id: string, reason: string) =>
    apiRequest<Brand>(`/brands/${id}/suspend`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  reinstate: (id: string, reason: string) =>
    apiRequest<Brand>(`/brands/${id}/reinstate`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  overrideBudget: (id: string, campaignId: string, newBudget: number, reason: string) =>
    apiRequest<void>(`/brands/${id}/campaigns/${campaignId}/budget-override`, {
      method: 'POST',
      body: JSON.stringify({ newBudget, reason }),
    }),
};

// ============================================
// CREATOR APIs
// ============================================

export const creatorApi = {
  /**
   * GET /creators (Task #3, AdminCreatorController) — paginated creator list for the admin Users
   * page. Returns `CreatorSummary` rows, NOT `Creator` — the backend's `CreatorSummaryDto` is a
   * lighter shape (no niche/engagementRate/instagramVerified/qualityScore; those are
   * `CreatorDetail`-only, see admin.types.ts). Params are built manually rather than spreading
   * `filters` into `URLSearchParams` for the same "undefined serializes as the literal string
   * 'undefined'" reason documented on `brandApi.list` above. Also note the backend query param is
   * `suspended`, not `isSuspended` (`AdminCreatorController.list()`), even though the frontend
   * filter type uses `isSuspended` for parity with `BrandFilters` — translated here.
   * `instagramVerified`/`niche`/`tier`/`minFollowers`/`maxFollowers` have no backend support yet
   * and are intentionally not sent.
   */
  list: (filters: CreatorFilters, page = 1, pageSize = 20) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (filters.search) params.set('search', filters.search);
    if (filters.applicationStatus) params.set('applicationStatus', filters.applicationStatus);
    if (filters.isSuspended !== undefined) params.set('suspended', String(filters.isSuspended));
    return apiRequest<PaginatedResponse<CreatorSummary>>(`/creators?${params}`);
  },

  getById: (id: string, signal?: AbortSignal) =>
    apiRequest<CreatorDetail>(`/creators/${id}`, { signal }),

  update: (id: string, data: Partial<Creator>) =>
    apiRequest<Creator>(`/creators/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  reviewApplication: (action: CreatorApplicationAction) =>
    apiRequest<Creator>(`/creators/${action.creatorId}/review-application`, {
      method: 'POST',
      body: JSON.stringify(action),
    }),

  adjustTier: (adjustment: CreatorTierAdjustment) =>
    apiRequest<Creator>(`/creators/${adjustment.creatorId}/tier`, {
      method: 'PUT',
      body: JSON.stringify(adjustment),
    }),

  forceInstagramReauth: (id: string) =>
    apiRequest<void>(`/creators/${id}/instagram/force-reauth`, { method: 'POST' }),

  suspend: (id: string, reason: string) =>
    apiRequest<Creator>(`/creators/${id}/suspend`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  reinstate: (id: string, reason: string) =>
    apiRequest<Creator>(`/creators/${id}/reinstate`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  getPendingApplications: (page = 1, pageSize = 20) =>
    apiRequest<PaginatedResponse<CreatorSummary>>(
      `/creators/applications/pending?page=${page}&pageSize=${pageSize}`
    ),
};

// ============================================
// CAMPAIGN APIs
// ============================================

export const campaignApi = {
  /**
   * Builds params manually rather than spreading `filters` straight into `URLSearchParams`
   * (same "undefined serializes as the literal string 'undefined'" pitfall documented on
   * `brandApi.list`/`creatorApi.list`/`disputeApi.list` above). `AdminCampaignController.list()`
   * (P2-7 MVP, see its class javadoc) takes no `@RequestParam`s at all yet — `search`/`status`/
   * `type`/`brandId`/`atRisk` from `CampaignFilters` have no backend support today, so they're
   * intentionally not sent as dead params, same as `industry`/`size` on `brandApi.list`. Only
   * `page`/`pageSize` go over the wire; `filters` stays client-side-only until server-side
   * filtering ships.
   */
  list: (filters: CampaignFilters, page = 1, pageSize = 20) => {
    void filters;
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    return apiRequest<PaginatedResponse<CampaignSummary>>(`/campaigns?${params}`);
  },

  /**
   * P2-7 MVP: `AdminCampaignController` (Vikram) only ships `GET /admin/campaigns` with no
   * pagination/filter/sort params — it returns a plain `CampaignSummaryDto[]` (unwrapped JSON
   * array), not the `{data, total, page, pageSize, totalPages}` envelope `list()` above expects.
   * `useCampaignList` calls this and keeps its existing client-side sort/filter/pagination.
   * Follow-up: fold this into `list()` once server-side filters/pagination ship.
   */
  listAll: () => apiRequest<CampaignSummary[]>('/campaigns'),

  getById: (id: string) =>
    apiRequest<CampaignDetail>(`/campaigns/${id}`),

  getAtRisk: () =>
    apiRequest<CampaignSummary[]>('/campaigns/at-risk'),

  getHypeOps: () =>
    apiRequest<{
      activeHype: number;
      totalSlots: number;
      filledSlots: number;
      avgFillRate: number;
      approvalsPerHour: number;
    }>('/campaigns/hype/ops'),
};

// ============================================
// FINANCE APIs
// ============================================

export const financeApi = {
  getRevenue: (startDate: string, endDate: string, groupBy: 'day' | 'week' | 'month') =>
    apiRequest<RevenueSnapshot[]>(
      `/finance/revenue?startDate=${startDate}&endDate=${endDate}&groupBy=${groupBy}`
    ),

  getEscrowSummary: () =>
    apiRequest<EscrowSummary>('/finance/escrow'),

  getPayoutQueue: (status?: string, page = 1, pageSize = 20) =>
    apiRequest<PaginatedResponse<PayoutQueueItem>>(
      `/finance/payouts?${new URLSearchParams({
        ...(status && { status }),
        page: String(page),
        pageSize: String(pageSize),
      })}`
    ),

  retryPayout: (id: string) =>
    apiRequest<PayoutQueueItem>(`/finance/payouts/${id}/retry`, { method: 'POST' }),

  getReconciliation: (date: string) =>
    apiRequest<ReconciliationItem[]>(`/finance/reconciliation?date=${date}`),

  resolveReconciliation: (id: string, resolution: 'MATCH' | 'WRITE_OFF', reason: string) =>
    apiRequest<ReconciliationItem>(`/finance/reconciliation/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ resolution, reason }),
    }),

  getTdsReport: (quarter: string) =>
    apiRequest<{ downloadUrl: string }>(`/finance/tds/26q?quarter=${quarter}`),

  // --------------------------------------------
  // Platform fee config — PlatformFeeAdminController (Vikram), SUPER_ADMIN
  // only, MFA-gated server-side (AdminContextService#requireRoleWithMfaSatisfied).
  // Mounted at /admin/finance/fee-config -> relative to API_BASE this is
  // /finance/fee-config (see PlatformFeeAdminController class javadoc).
  // --------------------------------------------

  // NOTE: return types are the raw wire shapes (percent fields typed `number | string`) — the
  // backend's BigDecimal percent fields can serialize as either. useFeeConfig.ts coerces these to
  // the numeric `PlatformFeeConfig`/`PlatformFeeHistoryEntry` app types before use.
  getFeeConfig: () =>
    apiRequest<PlatformFeeConfigWire>('/finance/fee-config'),

  getFeeConfigHistory: () =>
    apiRequest<PlatformFeeHistoryEntryWire[]>('/finance/fee-config/history'),

  /**
   * PUT /finance/fee-config. Sends `expectedEffectiveDate` (the config's `effectiveDate` as
   * last observed by this client) as an optimistic-concurrency token — see
   * `PlatformFeeUpdateRequest.expectedEffectiveDate` in admin.types.ts for the full rationale.
   *
   * ENFORCED server-side: `PlatformFeeConfigDtos.UpdatePlatformFeeConfigRequest` now declares
   * `expectedEffectiveDate` as a `@NotNull` field, and `PlatformFeeAdminService#update()`
   * unconditionally compares it against the freshly-loaded row's `effectiveDate` inside its own
   * transaction, throwing `FEE_CONFIG_CONFLICT` (HTTP 409) on any mismatch — so the realistic
   * "two SUPER_ADMINs submitting minutes apart" race now 409s reliably instead of silently
   * reverting the first admin's change. Hibernate `@Version` on `PlatformFeeConfig#version`
   * (V44 migration) remains as a defense-in-depth backstop for same-transaction-window races.
   * Because the token is mandatory server-side, this client MUST always send it (FeeControlPanel
   * echoes the currently-loaded `effectiveDate`); omitting it now yields a 400, not a silent
   * overwrite. The client's 409 handling below (surface + refresh, never overwrite) is correct.
   *
   * Bypasses the shared `apiRequest` helper for the error path on purpose:
   * that helper reads `error.message` off the raw response body, but
   * `GlobalExceptionHandler` always wraps errors as
   * `{ success:false, error: { code, message, ... } }` (even though this
   * controller's success path returns a raw unwrapped DTO) — so
   * `error.message` is always `undefined` and the real message lives at
   * `error.error.message`. Parsed correctly here so the 409 conflict path
   * gets an actual message instead of "undefined".
   */
  updateFeeConfig: async (payload: PlatformFeeUpdateRequest): Promise<{
    success: boolean;
    // Raw wire shape (BigDecimal percent fields may be `number | string`) — same caveat as
    // getFeeConfig/getFeeConfigHistory above. Caller (FeeControlPanel) doesn't consume `data`
    // directly; it re-pulls via `refresh()`, which goes through useFeeConfig's coercion.
    data?: PlatformFeeConfigWire;
    error?: string;
    /** True when this failure is the optimistic-lock conflict (HTTP 409 / FEE_CONFIG_CONFLICT) — the caller should prompt a reload, never retry-overwrite. */
    conflict?: boolean;
  }> => {
    const token = localStorage.getItem('admin_token');

    const response = await fetch(`${API_BASE}/finance/fee-config`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        ...(token && { Authorization: `Bearer ${token}` }),
      },
      body: JSON.stringify(payload),
    });

    if (response.ok) {
      let data: PlatformFeeConfigWire;
      try {
        data = (await response.json()) as PlatformFeeConfigWire;
      } catch {
        return { success: false, error: 'Malformed response from server' };
      }
      return { success: true, data };
    }

    const body = (await response.json().catch(() => null)) as {
      error?: { code?: string; message?: string };
    } | null;
    const message = body?.error?.message ?? 'Failed to update fee config';
    const conflict = response.status === 409 || body?.error?.code === 'FEE_CONFIG_CONFLICT';

    return { success: false, error: message, conflict };
  },
};

// ============================================
// ESCROW APIs
// ============================================

export const escrowApi = {
  getFlagged: () =>
    apiRequest<{
      id: string;
      campaignId: string;
      campaignName: string;
      amount: number;
      flagReason: string;
      createdAt: string;
    }[]>('/escrow/flagged'),

  release: (id: string, reason: string) =>
    apiRequest<void>(`/escrow/${id}/release`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  hold: (id: string, reason: string) =>
    apiRequest<void>(`/escrow/${id}/hold`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  refund: (id: string, amount: number, reason: string) =>
    apiRequest<void>(`/escrow/${id}/refund`, {
      method: 'POST',
      body: JSON.stringify({ amount, reason }),
    }),
};

// ============================================
// SUPPORT APIs
// ============================================

export const supportApi = {
  /**
   * Builds params manually rather than spreading `filters` straight into `URLSearchParams`
   * (same "undefined serializes as the literal string 'undefined'" pitfall documented on
   * `brandApi.list`/`creatorApi.list`/`disputeApi.list` above). All six `TicketFilters` fields
   * are supported by `AdminSupportController.list()` under matching param names.
   */
  list: (filters: TicketFilters, page = 1, pageSize = 20) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (filters.search) params.set('search', filters.search);
    if (filters.status) params.set('status', filters.status);
    if (filters.priority) params.set('priority', filters.priority);
    if (filters.userType) params.set('userType', filters.userType);
    if (filters.assignedTo) params.set('assignedTo', filters.assignedTo);
    if (filters.category) params.set('category', filters.category);
    return apiRequest<PaginatedResponse<SupportTicket>>(`/support/tickets?${params}`);
  },

  getById: (id: string) =>
    apiRequest<TicketDetail>(`/support/tickets/${id}`),

  update: (id: string, data: Partial<SupportTicket>) =>
    apiRequest<SupportTicket>(`/support/tickets/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
    }),

  reply: (id: string, content: string) =>
    apiRequest<TicketDetail>(`/support/tickets/${id}/reply`, {
      method: 'POST',
      body: JSON.stringify({ content }),
    }),

  escalate: (id: string, reason: string) =>
    apiRequest<SupportTicket>(`/support/tickets/${id}/escalate`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),

  assign: (id: string, adminId: string) =>
    apiRequest<SupportTicket>(`/support/tickets/${id}/assign`, {
      method: 'POST',
      body: JSON.stringify({ adminId }),
    }),

  getStats: () =>
    apiRequest<{
      open: number;
      inProgress: number;
      waitingUser: number;
      avgResponseTime: number;
      avgResolutionTime: number;
    }>('/support/stats'),
};

// ============================================
// MODERATION APIs
// ============================================

export const moderationApi = {
  getContentFlags: (status?: ContentFlag['status'], page = 1, pageSize = 20) =>
    apiRequest<PaginatedResponse<ContentFlag>>(
      `/moderation/flags?${new URLSearchParams({
        ...(status && { status }),
        page: String(page),
        pageSize: String(pageSize),
      })}`
    ),

  actionFlag: (id: string, action: ModerationAction) =>
    apiRequest<ContentFlag>(`/moderation/flags/${id}/action`, {
      method: 'POST',
      body: JSON.stringify(action),
    }),

  getPendingApprovals: (type?: string) =>
    apiRequest<ApprovalWorkflow[]>(
      `/moderation/approvals/pending${type ? `?type=${type}` : ''}`
    ),

  processApproval: (id: string, action: 'APPROVE' | 'REJECT', notes: string) =>
    apiRequest<ApprovalWorkflow>(`/moderation/approvals/${id}`, {
      method: 'POST',
      body: JSON.stringify({ action, notes }),
    }),

  getSuspensions: (status?: string) =>
    apiRequest<AccountSuspension[]>(
      `/moderation/suspensions${status ? `?status=${status}` : ''}`
    ),

  reviewAppeal: (id: string, action: 'REINSTATE' | 'UPHOLD', notes: string) =>
    apiRequest<AccountSuspension>(`/moderation/suspensions/${id}/appeal`, {
      method: 'POST',
      body: JSON.stringify({ action, notes }),
    }),
};

// ============================================
// DISPUTE APIs
// ============================================

// AdminDisputeController (Vikram) — mounted at /admin/disputes, resolving to
// /api/v1/admin/disputes via API_BASE. Task #4 (list) is live; resolve() now also
// settles escrow (release/refund/split) via EscrowService. No `GET /admin/disputes/:id`
// exists yet — AdminDisputeController only exposes `list()` and `resolve()`, so there is
// no `getById` here. Add one once the backend ships a single-dispute read endpoint.
export const disputeApi = {
  /**
   * Builds params manually rather than spreading `filters` straight into `URLSearchParams`
   * (same "undefined serializes as the literal string 'undefined'" pitfall documented on
   * `brandApi.list`/`creatorApi.list` above) — a cleared status filter must be an absent
   * param, not `status=undefined`, or `AdminDisputeController.list()` would read it as a
   * real (bogus) filter value.
   */
  list: (filters: DisputeFilters, page = 1, pageSize = 20) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (filters.status) params.set('status', filters.status);
    if (filters.campaignId) params.set('campaignId', filters.campaignId);
    if (filters.brandId) params.set('brandId', filters.brandId);
    if (filters.creatorId) params.set('creatorId', filters.creatorId);
    return apiRequest<DisputeListResponse>(`/disputes?${params}`);
  },

  /**
   * `resolution.creatorSplitPercent` (Task #5 — only required/consulted when
   * `resolution.resolution === DisputeStatus.RESOLVED_SPLIT`) flows through here for free:
   * `JSON.stringify` on the `DisputeResolution` object includes it whenever the caller set it
   * and simply omits the key when it's `undefined`, so no extra wiring is needed beyond the
   * type carrying the field (see admin.types.ts). The caller (`DisputeResolveModal`) is
   * responsible for only setting it for a split resolution and validating 0-100 client-side —
   * `EscrowService.adminSplitForDispute` is the authoritative server-side validation.
   */
  resolve: (id: string, resolution: DisputeResolution) =>
    apiRequest<DisputeDetail>(`/disputes/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify(resolution),
    }),
};

// ============================================
// AUDIT LOG APIs
// ============================================

export const auditApi = {
  /**
   * Builds params manually rather than spreading `filters` straight into `URLSearchParams`
   * (same "undefined serializes as the literal string 'undefined'" pitfall documented on
   * `brandApi.list`/`creatorApi.list`/`disputeApi.list` above). All five filter fields are
   * supported by `AuditLogController.list()` under matching param names.
   */
  list: (
    filters: { adminId?: string; entityType?: string; action?: string; startDate?: string; endDate?: string },
    page = 1,
    pageSize = 50
  ) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (filters.adminId) params.set('adminId', filters.adminId);
    if (filters.entityType) params.set('entityType', filters.entityType);
    if (filters.action) params.set('action', filters.action);
    if (filters.startDate) params.set('startDate', filters.startDate);
    if (filters.endDate) params.set('endDate', filters.endDate);
    return apiRequest<PaginatedResponse<AuditLogEntry>>(`/audit?${params}`);
  },

  getByEntity: (entityType: string, entityId: string) =>
    apiRequest<AuditLogEntry[]>(`/audit/entity/${entityType}/${entityId}`),
};

// ============================================
// ERROR LOG APIs
// ============================================

export const errorApi = {
  getRecent: (limit = 50) =>
    apiRequest<ErrorLogEntry[]>(`/errors/recent?limit=${limit}`),

  getById: (id: string) =>
    apiRequest<ErrorLogEntry>(`/errors/${id}`),

  resolve: (id: string) =>
    apiRequest<ErrorLogEntry>(`/errors/${id}/resolve`, { method: 'POST' }),

  getStats: () =>
    apiRequest<{
      total24h: number;
      critical24h: number;
      unresolved: number;
      topEndpoints: { endpoint: string; count: number }[];
    }>('/errors/stats'),
};

// ============================================
// EMAIL APIs
// ============================================

export const emailApi = {
  getQueue: (status?: string, page = 1, pageSize = 20) =>
    apiRequest<PaginatedResponse<EmailQueueItem>>(
      `/emails/queue?${new URLSearchParams({
        ...(status && { status }),
        page: String(page),
        pageSize: String(pageSize),
      })}`
    ),

  retry: (id: string) =>
    apiRequest<EmailQueueItem>(`/emails/queue/${id}/retry`, { method: 'POST' }),

  getTemplates: () =>
    apiRequest<{ id: string; name: string; subject: string }[]>('/emails/templates'),

  sendBulk: (templateId: string, recipientFilters: Record<string, unknown>) =>
    apiRequest<{ queued: number }>('/emails/send-bulk', {
      method: 'POST',
      body: JSON.stringify({ templateId, recipientFilters }),
    }),

  getStats: () =>
    apiRequest<{
      sent24h: number;
      failed24h: number;
      pending: number;
      avgDeliveryTime: number;
    }>('/emails/stats'),
};

// ============================================
// MARKETING APIs
// ============================================

export const marketingApi = {
  getAcquisition: (startDate: string, endDate: string) =>
    apiRequest<AcquisitionMetrics>(
      `/marketing/acquisition?startDate=${startDate}&endDate=${endDate}`
    ),

  getGrowth: () =>
    apiRequest<GrowthMetrics>('/marketing/growth'),

  getReputation: () =>
    apiRequest<PlatformReputationScore>('/marketing/reputation'),

  getReferrals: (page = 1, pageSize = 20) =>
    apiRequest<PaginatedResponse<{
      referrerId: string;
      referrerName: string;
      referredCount: number;
      convertedCount: number;
      revenueAttributed: number;
    }>>(`/marketing/referrals?page=${page}&pageSize=${pageSize}`),
};

// ============================================
// BILLING APIs (W6-1 — admin billing console)
// ============================================

/**
 * Admin billing console backend (AdminBillingController, Vikram).
 * Mounted at `/admin/billing` → resolves to `/api/v1/admin/billing` via API_BASE.
 *
 * Pattern: follows `brandApi.list` / `creatorApi.list` manual param building to avoid
 * the "undefined serializes as the literal string 'undefined'" pitfall (see those
 * javadocs above). Params are built explicitly, only appending when values are truthy.
 */
export const billingApi = {
  /**
   * GET /billing/subscriptions — paginated subscription list with optional filters.
   * Returns PaginatedSubscriptionResponse (data/total/page/pageSize/totalPages envelope).
   */
  listSubscriptions: (
    page = 1,
    pageSize = 20,
    status?: string,
    search?: string
  ) => {
    const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) });
    if (status) params.set('status', status);
    if (search) params.set('search', search);
    return apiRequest<PaginatedSubscriptionResponse>(`/billing/subscriptions?${params}`);
  },

  /**
   * GET /billing/metrics — aggregate MRR/ARR/churn metrics.
   */
  getMetrics: () =>
    apiRequest<BillingMetrics>('/billing/metrics'),

  /**
   * POST /billing/comp — grant complimentary Pro subscription.
   * Reason must be >=10 chars (server-side @Size validation).
   */
  grantComp: (request: CompSubscriptionRequest) =>
    apiRequest<AdminSubscriptionActionResult>('/billing/comp', {
      method: 'POST',
      body: JSON.stringify(request),
    }),

  /**
   * POST /billing/override — override a workspace's plan assignment.
   * Reason must be >=10 chars (server-side @Size validation).
   */
  overridePlan: (request: OverrideSubscriptionRequest) =>
    apiRequest<AdminSubscriptionActionResult>('/billing/override', {
      method: 'POST',
      body: JSON.stringify(request),
    }),
};
