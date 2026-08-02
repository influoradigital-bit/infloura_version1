/**
 * Meera AI API Client - Centralized endpoint definitions
 * ----------------------------------------------------------------------------
 * P12 Resolution: All paths match the actual MeeraController.java routes.
 * Doc 02 (API-CONTRACT-BRAND.md) is authoritative for endpoint shapes.
 *
 * The browser only calls:
 *   - Spring PUBLIC endpoints (/api/v1/meera/*, /api/v1/wallet/*)
 *   - Python SSE stream edge (streamUrl with streamToken)
 *
 * The browser NEVER calls /internal/meera/* (Python-to-Spring only).
 */

import { ApiError, extractInsufficientFundsDetails, isApiLive, type ApiErrorPayload } from './api';

// ---------------------------------------------------------------------------
// Environment / config
// ---------------------------------------------------------------------------

const API_BASE_URL =
  (import.meta as any).env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

/**
 * Python AI service stream URL. In production this is returned by Spring in
 * the sendTurn response (streamUrl field). For mock mode, this is not used.
 */
const MEERA_STREAM_BASE_URL =
  (import.meta as any).env?.VITE_MEERA_STREAM_URL || 'https://ai.influora.internal';

// ---------------------------------------------------------------------------
// Types (match 02-API-CONTRACT-BRAND.md exactly)
// ---------------------------------------------------------------------------

/** Session start response (02 section 1.1) */
export interface MeeraSessionResponse {
  conversationId: string;
  status: 'ACTIVE' | 'ANALYZING';
  brandProfileStatus: 'PENDING' | 'ANALYZING' | 'READY' | 'ERROR';
  credits: {
    remaining: number;
    unlimited: boolean;
  };
}

/** Send turn response (02 section 1.2, MeeraDtos.SendTurnResponse) */
export interface MeeraTurnResponse {
  messageId: string;
  /**
   * Id of the ASSISTANT message Spring already persisted for this turn.
   * Present on the synchronous (A4) backend flow, where the reply is
   * generated Java->Python before this response returns.
   */
  assistantMessageId?: string;
  streamToken: string;
  streamUrl: string;
  creditsRemaining: number;
  /**
   * The authoritative assistant reply, already generated AND persisted by
   * Spring's synchronous Java->Python turn (MeeraSessionService A4 flow).
   * When present, the turn is complete — opening the SSE stream would run a
   * SECOND paid generation of a reply that already exists, so the client
   * must render this directly instead. Absent only if the backend moves to
   * a stream-first turn split (mint token, return immediately, browser
   * streams the one-and-only generation).
   */
  reply?: string;
  /**
   * Workspace id for the stream request body (chat.py requires
   * `workspace_id` and 403s a token/body conversation mismatch). Only
   * needed — and only expected to be present — on a stream-first backend;
   * the current synchronous flow doesn't return it and doesn't need it.
   */
  workspaceId?: string;
  /**
   * SECURITY FIX #1 (docs/security/meera-onbehalf-auth-security-design.md
   * §2): the dedicated, per-turn, scoped on-behalf credential Spring mints
   * alongside `streamToken`. MUST be forwarded verbatim as `onbehalf_jwt` in
   * the SSE stream body (`MeeraChatPanel.tsx`'s `handleLiveSend`) instead of
   * reading the user's full access token out of `localStorage` — that old
   * path handed influora-ai (and anything downstream of it) a durable,
   * full-account-scope credential. Only present on the stream-first backend,
   * same as `workspaceId`.
   */
  onBehalfToken?: string;
}

/** Credit status response (02 section 1.3) */
export interface MeeraCreditStatus {
  creditsRemaining: number;
  monthlyAllotment: number;
  unlimited: boolean;
  unlimitedUntil: string | null;
  cycleStart: string;
  state: 'FREE' | 'UNLIMITED' | 'EXHAUSTED';
}

/** Brand profile response (02 section 1.7) */
export interface MeeraBrandProfile {
  workspaceId: string;
  websiteUrl: string | null;
  analysisStatus: 'PENDING' | 'ANALYZING' | 'READY' | 'ERROR';
  nicheTags: string[] | null;
  productCatalog: unknown | null;
  analysisError: string | null;
}

/**
 * Escrow fund response (02 section 1.4).
 * [FIX: double-charge, 2026-07-26] `razorpayOrderId` is now optional — the
 * server funds escrow immediately from the wallet balance it already
 * required (EscrowService.initiateFund) instead of creating a second
 * Razorpay order for the same amount, so the common response is
 * `status: 'FUNDED'` with no order at all. `undefined` (the field is
 * omitted, not sent as null — `@JsonInclude(NON_NULL)`) means "already
 * funded, nothing to check out."
 */
export interface MeeraEscrowFundResponse {
  escrowHoldId: string;
  amount: number;
  currency: 'INR';
  razorpayOrderId?: string;
  status: 'PENDING' | 'FUNDED';
}

/**
 * POST /meera/voice/transcribe success payload, parsed from the backend's
 * flat `{ raw_transcript, cleaned_text, lang_detected, fallback }` shape
 * into the camelCase this codebase's TS interfaces otherwise use. `fallback`
 * itself is not surfaced here — `meeraApi.transcribe` collapses it to a
 * `null` return so callers have exactly one thing to check.
 */
export interface MeeraTranscribeResult {
  rawTranscript: string;
  cleanedText: string;
  langDetected?: string;
}

/** Escrow status response (02 section 1.5) */
export interface MeeraEscrowStatus {
  escrowHoldId: string;
  status: 'PENDING' | 'FUNDED' | 'RELEASED' | 'CANCELLED';
  amount: number;
  currency: 'INR';
  campaignId: string;
  milestoneId: string | null;
  fundedAt: string | null;
}

export interface MeeraTokenEvent {
  text: string;
}

export interface MeeraThinkingEvent {
  step: string;
  done: boolean;
}

export interface MeeraToolStartEvent {
  name: string;
  input: Record<string, unknown>;
}

export interface MeeraToolResultEvent {
  name: string;
  status: 'ok' | 'error';
  data?: unknown;
}

export interface MeeraDoneEvent {
  finish_reason: 'stop' | 'tool_use' | 'max_tokens';
}

export interface MeeraErrorEvent {
  code: string;
  fallback: 'text';
  message?: string;
}

/**
 * Tool result payloads. Wire shape verified against the actual Spring DTOs
 * (`influora-api/.../web/dto/meera/MeeraToolDtos.java`), NOT the (stale)
 * `02-API-CONTRACT-BRAND.md` §3 prose — Spring `ApiResponse.ok(DTO)` →
 * Python `spring.py` unwraps `.data` → `loop.py` forwards it verbatim in the
 * SSE `tool_result` frame, so these interfaces are the Java records' JSON
 * shape one-for-one. Kept in sync via QA/Vikram's DTO fix (2026-07-17).
 */
export interface ShowCreatorsPayload {
  /** `MeeraToolDtos.ShowCreatorsResult` — no separate "matched total", the array length IS the count. */
  creators: Array<{
    creatorProfileId: string;
    displayName: string;
    /** Nullable on the DTO (`String`) — omitted from JSON when null. */
    city?: string;
    /** Nullable on the DTO (`List<String>`) — omitted from JSON when null. */
    categories?: string[];
    totalFollowers: number;
    /** Nullable on the DTO (`BigDecimal`) — omitted from JSON when null. */
    engagementRate?: number;
    verified: boolean;
  }>;
}

/** `MeeraToolDtos.CalculateBudgetResult` — advisory suggestion, not a locked-in fee breakdown. */
export interface CalculateBudgetPayload {
  suggestedPoolTotal: number;
  suggestedPerCreatorRate: number;
  suggestedCreatorCount: number;
  currency: string;
  /** Nullable on the DTO (`String`) — omitted from JSON when null. */
  rationale?: string;
}

export interface CreateCampaignPayload {
  campaignId: string;
  status: 'DRAFT';
  serverBudget: number;
}

/** `MeeraToolDtos.RequestPaymentResult` — no `escrowHoldId`/`razorpayOrderId`/`action` on this DTO. */
export interface RequestPaymentPayload {
  status: string;
  campaignIntentId: string;
  serverAmount: number;
  currency: string;
  confirmActionUrl: string;
  replay: boolean;
}

/** `MeeraToolDtos.ConfirmLaunchResult` — the `confirm_launch` tool's own result, not a live dashboard-stats feed. */
export interface ConfirmLaunchPayload {
  campaignId: string;
  status: string;
  creatorsInvited: number;
  replay: boolean;
}

/**
 * `MeeraToolDtos.GetCampaignPerformanceResult` — locked contract per
 * `wiki/build/phase2-priya-review.md` §2 Q2 (Ash's F1/F3 concur,
 * `wiki/build/phase2-ash-review.md`). Every number here is server-computed;
 * this DTO is the ONLY source of truth for the performance card —
 * `StagePerformance` never derives `roi` from `attributedRevenueInr /
 * spendInr` itself (SR-1: no frontend arithmetic over money-adjacent
 * numbers, per Ash's F1/B1 ruling).
 *
 * `provenance` is a SINGLE top-level 2-state tag for the whole result, not
 * per-field (`roiSource`/`responseRateSource`/etc. were dropped at design
 * review — Priya Q1/Ash Q1). v1 always emits `PLATFORM_VERIFIED` since only
 * verified numbers are ever surfaced; `SELF_REPORTED` is reserved for a
 * future fast-follow.
 *
 * `responseRate`/`avgCreatorScore` are typed OPTIONAL: as of this writing
 * Vikram's backend design doc (`wiki/build/phase2-backend-design.md`) had
 * not landed an implementation changes-log entry, so per Priya's B1
 * nice-to-have these two may be cut from v1 rather than shipped. `roi` +
 * `provenance` are the guaranteed core (Ash B1: "ROI must be server-computed
 * ... responseRate/avgCreatorScore must each get a defined server
 * derivation + source tag OR be cut for v1"). Re-verify field names against
 * the real Java DTO once Vikram's changes log confirms — do not widen this
 * type on a guess.
 */
export interface CampaignPerformancePayload {
  campaignId: string;
  creatorCount: number;
  spendInr: number;
  /** Nullable on the DTO — zero PLATFORM_VERIFIED deliverable-metric rows. */
  verifiedReach?: number;
  attributedRevenueInr: number;
  settledCommissionInr: number;
  /**
   * Server-computed ratio (1.4 = 140% return; Priya Q2: ratio, not a
   * percentage or INR delta). `null` when spend is zero or revenue is
   * unavailable — render "not enough data yet", never fall back to a
   * client-computed guess.
   */
  roi: number | null;
  /** 0..1 (Priya Q2). Optional — may be absent in v1; guard every read. */
  responseRate?: number;
  /** 0..100, same scale as `CreatorScoresResponse` (Priya Q2). Optional — may be absent in v1; guard every read. */
  avgCreatorScore?: number;
  /** Single top-level tag for the whole result — see doc comment above. */
  provenance: 'PLATFORM_VERIFIED' | 'SELF_REPORTED';
  /** PII-stripped by construction (guardrail 6) — opaque milestone id + numeric metrics only, no creator name/handle. */
  deliverables: Array<{
    milestoneId: string;
    reach?: number;
    impressions?: number;
    engagements?: number;
  }>;
}

// ---------------------------------------------------------------------------
// Tool-result payload type guards
// ----------------------------------------------------------------------------
// `MeeraToolResultEvent.data` is `unknown` on the wire (04 §4) — the SSE
// stream is trusted transport, not a typed one. Every consumer that wants to
// render a specific payload shape narrows it through one of these guards
// first instead of an unchecked `as` cast, so a malformed/unexpected payload
// falls back to "no data yet" (Living Canvas loading state) rather than
// rendering `undefined` fields or throwing.
// ---------------------------------------------------------------------------

export function isShowCreatorsPayload(data: unknown): data is ShowCreatorsPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<ShowCreatorsPayload>;
  return Array.isArray(d.creators);
}

export function isCalculateBudgetPayload(data: unknown): data is CalculateBudgetPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<CalculateBudgetPayload>;
  return (
    typeof d.suggestedPoolTotal === 'number' &&
    typeof d.suggestedPerCreatorRate === 'number' &&
    typeof d.suggestedCreatorCount === 'number'
  );
}

export function isRequestPaymentPayload(data: unknown): data is RequestPaymentPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<RequestPaymentPayload>;
  return typeof d.serverAmount === 'number';
}

export function isConfirmLaunchPayload(data: unknown): data is ConfirmLaunchPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<ConfirmLaunchPayload>;
  return typeof d.campaignId === 'string' && typeof d.status === 'string';
}

/**
 * `responseRate`/`avgCreatorScore` are deliberately NOT checked here — they
 * are optional on `CampaignPerformancePayload` (see doc comment) and may be
 * absent in v1. `roi` accepts `null` (zero-spend/no-revenue case) so the
 * guard only checks its type, not truthiness.
 */
export function isCampaignPerformancePayload(data: unknown): data is CampaignPerformancePayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<CampaignPerformancePayload>;
  return (
    typeof d.campaignId === 'string' &&
    (d.roi === null || typeof d.roi === 'number') &&
    (d.provenance === 'PLATFORM_VERIFIED' || d.provenance === 'SELF_REPORTED')
  );
}

/**
 * `present_options` — a DISPLAY-only response pattern (not a Spring tool): Meera
 * calls it to render a small set of choices as tappable cards in chat instead of
 * listing them in prose. The loop echoes the options straight back (loop.py's
 * local branch), so this is the exact shape Meera supplied.
 */
export interface OptionsPayload {
  title: string;
  options: Array<{
    key: string;
    label: string;
    why: string;
    budget_hint?: string;
    recommended?: boolean;
  }>;
}

export function isOptionsPayload(data: unknown): data is OptionsPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<OptionsPayload>;
  return typeof d.title === 'string' && Array.isArray(d.options);
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

function getToken(): string | null {
  return localStorage.getItem('brand_token');
}

async function request<T>(
  method: 'GET' | 'POST',
  path: string,
  opts: {
    body?: unknown;
    idempotencyKey?: string;
    query?: Record<string, string | number | undefined>;
  } = {}
): Promise<T> {
  const url = new URL(`${API_BASE_URL}${path}`);
  if (opts.query) {
    Object.entries(opts.query).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') {
        url.searchParams.set(k, String(v));
      }
    });
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

  const res = await fetch(url.toString(), {
    method,
    headers,
    credentials: 'include',
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });

  let envelope: { success: boolean; data?: T; error?: ApiErrorPayload };
  try {
    envelope = await res.json();
  } catch {
    throw new ApiError('NETWORK_ERROR', `Invalid JSON from ${path}`, res.status);
  }

  if (!res.ok || !envelope.success) {
    throw new ApiError(
      envelope.error?.code || 'UNKNOWN',
      envelope.error?.message || res.statusText,
      res.status,
      // [SEC: MF-1 follow-up] `POST /wallet/escrow/fund`'s INSUFFICIENT_FUNDS 402 rides this
      // path (fundEscrow uses this local `request`, not HttpClient in api.ts) — carry the
      // server-computed shortfall through so useEscrowFund never has to re-estimate it.
      extractInsufficientFundsDetails(envelope.error)
    );
  }

  return envelope.data as T;
}

// ---------------------------------------------------------------------------
// Mock helpers
// ---------------------------------------------------------------------------

const delay = (ms = 400) => new Promise((r) => setTimeout(r, ms));

/**
 * `crypto.randomUUID` only exists in secure contexts (https / localhost) — an
 * http:// staging host would throw. Idempotency keys just need per-click
 * uniqueness, not crypto strength, so fall back to a timestamp+random id.
 */
function safeRandomUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `idk-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

// ---------------------------------------------------------------------------
// API methods
// ---------------------------------------------------------------------------

export const meeraApi = {
  /**
   * POST /meera/sessions - Start or resume a Meera session
   * Returns conversationId and brand profile status
   */
  startSession: async (): Promise<MeeraSessionResponse> => {
    if (!isApiLive()) {
      await delay();
      return {
        conversationId: 'mock_conv_001',
        status: 'ACTIVE',
        brandProfileStatus: 'READY',
        credits: { remaining: 100, unlimited: false },
      };
    }
    return request<MeeraSessionResponse>('POST', '/meera/sessions');
  },

  /**
   * POST /meera/sessions/{conversationId}/messages - Send a turn
   * Returns streamToken + streamUrl for SSE connection
   */
  sendTurn: async (
    conversationId: string,
    content: string
  ): Promise<MeeraTurnResponse> => {
    if (!isApiLive()) {
      await delay();
      return {
        messageId: `mock_msg_${Date.now()}`,
        assistantMessageId: `mock_msg_${Date.now()}_assistant`,
        streamToken: 'mock_stream_token',
        streamUrl: `${MEERA_STREAM_BASE_URL}/stream`,
        creditsRemaining: 99,
        reply: 'This is a placeholder reply in mock mode.',
        onBehalfToken: 'mock_onbehalf_token',
      };
    }
    // Spring's POST /meera/sessions/{id}/messages requires an Idempotency-Key
    // header (MeeraController) and 400s without it. The panel never re-POSTs a
    // failed turn (double-spend guard), so a fresh key per call is correct.
    // (Kavya QA: if a retry path is ever added, the SAME key must be reused
    // across retries of one logical turn or the backend dedupe is bypassed.)
    return request<MeeraTurnResponse>('POST', `/meera/sessions/${conversationId}/messages`, {
      body: { content },
      idempotencyKey: safeRandomUUID(),
    });
  },

  /**
   * GET /meera/credits - Get credit status for the workspace
   */
  getCredits: async (): Promise<MeeraCreditStatus> => {
    if (!isApiLive()) {
      await delay();
      return {
        creditsRemaining: 100,
        monthlyAllotment: 100,
        unlimited: false,
        unlimitedUntil: null,
        cycleStart: '2026-07-01',
        state: 'FREE',
      };
    }
    return request<MeeraCreditStatus>('GET', '/meera/credits');
  },

  /**
   * GET /meera/brand-profile - Get brand profile / analysis status
   */
  getBrandProfile: async (): Promise<MeeraBrandProfile> => {
    if (!isApiLive()) {
      await delay();
      return {
        workspaceId: 'mock_ws_001',
        websiteUrl: 'kavalaskincare.com',
        analysisStatus: 'READY',
        nicheTags: ['skincare', 'beauty', 'organic'],
        productCatalog: { products: [{ name: 'Vitamin C Serum', price: 899 }] },
        analysisError: null,
      };
    }
    return request<MeeraBrandProfile>('GET', '/meera/brand-profile');
  },

  /**
   * POST /wallet/escrow/fund - Fund escrow for a campaign
   * SECURITY: No amount in body - server re-derives from campaignId
   * REQUIRED: Idempotency-Key header (client UUID)
   */
  fundEscrow: async (
    campaignId: string,
    idempotencyKey: string,
    milestoneId?: string
  ): Promise<MeeraEscrowFundResponse> => {
    if (!isApiLive()) {
      await delay(800);
      // Mirrors the live server's actual behavior post-fix: escrow is funded immediately
      // from the wallet balance it already required, no Razorpay order/Checkout step.
      return {
        escrowHoldId: `mock_escrow_${Date.now()}`,
        amount: 17250,
        currency: 'INR',
        status: 'FUNDED',
      };
    }
    return request<MeeraEscrowFundResponse>('POST', '/wallet/escrow/fund', {
      body: { campaignId, milestoneId: milestoneId ?? null },
      idempotencyKey,
    });
  },

  /**
   * GET /wallet/escrow/{escrowHoldId} - Get escrow status
   */
  getEscrowStatus: async (escrowHoldId: string): Promise<MeeraEscrowStatus> => {
    if (!isApiLive()) {
      await delay();
      return {
        escrowHoldId,
        status: 'FUNDED',
        amount: 17250,
        currency: 'INR',
        campaignId: 'mock_campaign_001',
        milestoneId: null,
        fundedAt: new Date().toISOString(),
      };
    }
    return request<MeeraEscrowStatus>('GET', `/wallet/escrow/${escrowHoldId}`);
  },

  /**
   * GET /meera/sessions/{conversationId}/messages (no `after`) — full turn
   * history for a conversation, oldest first. Used to rehydrate the chat panel
   * on mount so the transcript survives a tab switch / reload (R3b). The
   * backend returns the full history when `after` is omitted
   * (MeeraController#messages). Returns [] in mock mode (the scripted panel
   * owns its own transcript there).
   */
  getHistory: async (
    conversationId: string
  ): Promise<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>> => {
    if (!isApiLive()) return [];
    return request<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>>(
      'GET',
      `/meera/sessions/${conversationId}/messages`
    );
  },

  /**
   * GET /meera/sessions/{conversationId}/messages?after={messageId}
   * Non-streaming fallback for fetching finalized turn
   */
  getMessagesAfter: async (
    conversationId: string,
    afterMessageId: string
  ): Promise<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>> => {
    if (!isApiLive()) {
      await delay();
      return [
        {
          id: `mock_reply_${Date.now()}`,
          role: 'ASSISTANT',
          content: 'This is a fallback reply fetched after stream failure.',
        },
      ];
    }
    return request<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>>(
      'GET',
      `/meera/sessions/${conversationId}/messages`,
      { query: { after: afterMessageId } }
    );
  },

  /**
   * POST /meera/voice/speak - Server-side TTS (Sarvam) for a Meera reply.
   *
   * Returns the raw WAV audio as a Blob on success, or `null` for every
   * "no audio available" case: mock mode (browser voice owns mock TTS),
   * the backend's own `{"fallback": true}` response, a non-2xx status, a
   * non-`audio/*` content type, or any network/parsing failure. This method
   * never throws — `useVoiceOutput` treats a `null` return as "fall back to
   * SpeechSynthesis", so a thrown error here would break that contract.
   *
   * Deliberately bypasses the shared `request()` helper: that helper assumes
   * a JSON `{ success, data }` envelope, but this endpoint's success body is
   * raw audio bytes, not JSON.
   *
   * `lang` (W3): the BCP-47-ish code `/meera/voice/transcribe` returned as
   * `lang_detected` for the turn being replied to (e.g. `hi-IN`), so the
   * spoken reply matches the language the user actually spoke instead of
   * always defaulting to English. Optional — omitted when no detection is
   * available (e.g. the browser STT fallback never produced one), in which
   * case the backend's own default (`en-IN`, `voice.py`'s
   * `body.get("lang", "en-IN")`) applies.
   */
  speak: async (text: string, lang?: string): Promise<Blob | null> => {
    if (!isApiLive()) return null;

    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      const token = getToken();
      if (token) headers.Authorization = `Bearer ${token}`;

      const res = await fetch(`${API_BASE_URL}/meera/voice/speak`, {
        method: 'POST',
        headers,
        credentials: 'include',
        body: JSON.stringify(lang ? { text, lang } : { text }),
      });

      if (!res.ok) return null;

      const contentType = res.headers.get('content-type') || '';
      if (!contentType.startsWith('audio/')) return null;

      return await res.blob();
    } catch {
      return null;
    }
  },

  /**
   * POST /meera/voice/transcribe - Server-side STT (Sarvam) for the
   * composer's mic input. Mirrors `speak()`'s discipline in reverse: sends a
   * recorded clip, gets text back.
   *
   * Returns the parsed transcript on success, or `null` for every "no
   * transcript available" case: mock mode (browser STT owns mock input), the
   * backend's own `{"fallback": true}` soft-fail, a non-2xx status, an
   * unparsable body, or any network failure. This method never throws —
   * `useVoiceInput` treats a `null` return as "fall back to
   * webkitSpeechRecognition", so a thrown error here would break that
   * contract.
   *
   * Deliberately bypasses the shared `request()` helper: that helper assumes
   * a JSON `{ success, data }` envelope and a JSON request body, but this
   * endpoint takes multipart form data (a single `audio` file part) and
   * returns a flat JSON object, not the envelope shape.
   */
  transcribe: async (audio: Blob): Promise<MeeraTranscribeResult | null> => {
    if (!isApiLive()) return null;

    try {
      const headers: Record<string, string> = {};
      const token = getToken();
      if (token) headers.Authorization = `Bearer ${token}`;

      const formData = new FormData();
      // Field name is contractual — the backend reads the multipart part
      // named `audio`. No workspace_id in the body; the server derives it
      // from the auth token, same as every other /meera/* call.
      formData.append('audio', audio);

      const res = await fetch(`${API_BASE_URL}/meera/voice/transcribe`, {
        method: 'POST',
        headers,
        credentials: 'include',
        body: formData,
      });

      if (!res.ok) return null;

      let body: {
        raw_transcript?: unknown;
        cleaned_text?: unknown;
        lang_detected?: unknown;
        fallback?: unknown;
      };
      try {
        body = await res.json();
      } catch {
        return null;
      }

      if (body.fallback === true) return null;

      const rawTranscript = typeof body.raw_transcript === 'string' ? body.raw_transcript : '';
      const cleanedText = typeof body.cleaned_text === 'string' ? body.cleaned_text : '';
      // Nothing usable came back — treat like a soft-fail rather than
      // handing the caller two empty strings to deal with.
      if (!rawTranscript && !cleanedText) return null;

      return {
        rawTranscript,
        cleanedText,
        langDetected: typeof body.lang_detected === 'string' ? body.lang_detected : undefined,
      };
    } catch {
      return null;
    }
  },
};

export default meeraApi;
