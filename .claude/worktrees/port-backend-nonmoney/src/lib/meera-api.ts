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

/** Send turn response (02 section 1.2) */
export interface MeeraTurnResponse {
  messageId: string;
  streamToken: string;
  streamUrl: string;
  creditsRemaining: number;
  /** Placeholder reply for non-streaming fallback */
  placeholderReply?: string;
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

/** Tool result payloads (02 section 3) */
export interface ShowCreatorsPayload {
  creators: Array<{
    creatorId: string;
    displayName: string;
    followers: number;
    engagementRate: number;
  }>;
  matchedTotal: number;
}

export interface CalculateBudgetPayload {
  pool: number;
  perCreator: number;
  platformFee: number;
  total: number;
}

export interface CreateCampaignPayload {
  campaignId: string;
  status: 'DRAFT';
  serverBudget: number;
}

export interface RequestPaymentPayload {
  escrowHoldId: string;
  serverAmount: number;
  currency: 'INR';
  razorpayOrderId: string;
  action: 'AWAIT_HUMAN_CONFIRM';
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
        streamToken: 'mock_stream_token',
        streamUrl: `${MEERA_STREAM_BASE_URL}/stream`,
        creditsRemaining: 99,
        placeholderReply: 'This is a placeholder reply in mock mode.',
      };
    }
    return request<MeeraTurnResponse>('POST', `/meera/sessions/${conversationId}/messages`, {
      body: { content },
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
};

export default meeraApi;
