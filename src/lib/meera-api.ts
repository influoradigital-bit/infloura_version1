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

import { ApiError, isApiLive } from './api';

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

/** Escrow fund response (02 section 1.4) */
export interface MeeraEscrowFundResponse {
  escrowHoldId: string;
  amount: number;
  currency: 'INR';
  razorpayOrderId: string;
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

/** SSE event types from Python (04 section 4) */
export type MeeraSSEEventType =
  | 'token'
  | 'thinking'
  | 'tool_start'
  | 'tool_result'
  | 'prompt_meta'
  | 'done'
  | 'error';

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

  let envelope: { success: boolean; data?: T; error?: { code: string; message: string } };
  try {
    envelope = await res.json();
  } catch {
    throw new ApiError('NETWORK_ERROR', `Invalid JSON from ${path}`, res.status);
  }

  if (!res.ok || !envelope.success) {
    throw new ApiError(
      envelope.error?.code || 'UNKNOWN',
      envelope.error?.message || res.statusText,
      res.status
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
      return {
        escrowHoldId: `mock_escrow_${Date.now()}`,
        amount: 17250,
        currency: 'INR',
        razorpayOrderId: `order_mock_${Date.now()}`,
        status: 'PENDING',
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
   */
  speak: async (text: string): Promise<Blob | null> => {
    if (!isApiLive()) return null;

    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      const token = getToken();
      if (token) headers.Authorization = `Bearer ${token}`;

      const res = await fetch(`${API_BASE_URL}/meera/voice/speak`, {
        method: 'POST',
        headers,
        credentials: 'include',
        body: JSON.stringify({ text }),
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
