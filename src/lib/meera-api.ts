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

import {
  ApiError,
  PaymentsUnavailableError,
  extractInsufficientFundsDetails,
  isApiLive,
  isMoneyActionBlocked,
  type ApiErrorPayload,
  type BriefExtraction,
  type DealRisksResponse,
  type PackageQuote,
  type RiskFlag,
} from './api';
// F-0551 — the shared in-memory access-token store. Imported from auth-session (not api.ts) for
// the same reason api.ts reads it from there: that module is the single owner of the live-mode
// token slot, so both API layers resolve the same credential instead of each keeping its own.
import { getMemoryAccessToken, LIVE_SESSION_TOKEN_HINT } from './auth-session';
// T-MEERA-CREATOR-PHASE-B §8.2 — `DraftReplyPayload.deal_terms` carries the same camelCase
// `DealTermsDto` shape `deals.counter`/`deals.create` already use (SPEC.md §8.1). `DealTerms` is
// not re-exported from api.ts (by design — see api.ts's own note above `deals`), so import it
// straight from types.ts the way api.ts itself does.
import type { DealTerms } from './types';

// ---------------------------------------------------------------------------
// Environment / config
// ---------------------------------------------------------------------------

const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

/**
 * Python AI service stream URL. In production this is returned by Spring in
 * the sendTurn response (streamUrl field). For mock mode, this is not used.
 */
const MEERA_STREAM_BASE_URL =
  import.meta.env?.VITE_MEERA_STREAM_URL || 'https://ai.influora.internal';

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

/**
 * POST /ai/shoot-check/frame success payload — Level 2 "Check my frame" still-image analysis for
 * the creator Shoot Check screen (`ShootCheckPanel.tsx`). Three flat string lists rather than
 * anything more structured: `fixes` (must-do corrections), `settings` (camera/app setting
 * suggestions), `ok` (what's already fine) — the panel renders each as its own labeled group.
 */
export interface MeeraShootCheckFrameResult {
  fixes: string[];
  settings: string[];
  ok: string[];
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
    /** CreatorProfile.verified — an identity flag, NOT a claim about the follower numbers. */
    verified: boolean;
    /**
     * EV-008 — MeeraToolDtos.CreatorSummary.followersSource. The ONLY field that may back a
     * "verified stats" badge: VERIFIED = Meta-synced platforms; IMPORTED = Marketplace/admin
     * import (label it); NONE = nothing counted.
     */
    followersSource?: 'VERIFIED' | 'IMPORTED' | 'NONE';
  }>;
}

/**
 * `MeeraToolDtos.CalculateBudgetResult` — advisory suggestion, not a locked-in fee breakdown.
 *
 * P1-12 (2026-09-13): the money fields are now OPTIONAL, because the executor refuses to quote
 * when there is no real niche rate band to quote from — and that refusal is the common case in
 * production, not a rare one. The DTO is `@JsonInclude(NON_NULL)`, so on that path
 * `suggestedPoolTotal` and `suggestedPerCreatorRate` are absent from the wire entirely. Declaring
 * them as required `number` (as this did until P1-12) would make `isCalculateBudgetPayload`
 * return false for every refusal, and `ToolResultRenderer` would silently render nothing —
 * exactly the kind of invisible FE↔DTO seam that made `get_campaign_performance` uncallable for
 * weeks. Read `rateBasis` first, then the numbers.
 */
export interface CalculateBudgetPayload {
  /** Absent when `rateBasis === 'insufficient_data'`. */
  suggestedPoolTotal?: number;
  /** Absent when `rateBasis === 'insufficient_data'`. */
  suggestedPerCreatorRate?: number;
  suggestedCreatorCount: number;
  currency: string;
  /** Nullable on the DTO (`String`) — omitted from JSON when null. */
  rationale?: string;
  /**
   * `'platform_rate_band'` — the figures are the median/range of real completed collaborations in
   * the brand's niche. `'insufficient_data'` — we don't have enough settled deals in that niche to
   * know what creators charge, so NO figure is quoted and the card says so.
   */
  rateBasis?: 'platform_rate_band' | 'insufficient_data';
  /** Low end of the real band; absent when there is no band. */
  perCreatorRateMin?: number;
  /** High end of the real band; absent when there is no band. */
  perCreatorRateMax?: number;
  /** Distinct creators behind the band (aggregate count only). */
  rateSampleSize?: number;
  /** The niche the band was computed for. */
  rateNiche?: string;
}

/**
 * `MeeraToolDtos.CreateCampaignResult` (MeeraToolDtos.java:66-67) — exactly
 * `(campaignId, campaignIntentId, status, replay)`.
 *
 * This declared a required `serverBudget: number` until 2026-08-16. It has never existed on the
 * wire in influora-api or influora-ai, so every draft rendered the "budget not set yet" branch,
 * and `campaignIntentId`/`replay` — which the backend really does send — were invisible to the FE.
 */
export interface CreateCampaignPayload {
  campaignId: string;
  campaignIntentId?: string;
  status: 'DRAFT';
  /** True when the tool call was replayed from the idempotency cache rather than re-executed. */
  replay?: boolean;
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
  // `suggestedCreatorCount` is an `int` on the DTO and is therefore ALWAYS on the wire, on both
  // the quoted and the refused path — it is the only field that can identify this payload.
  // P1-12: the money fields are deliberately NOT part of this check; requiring them would make a
  // refusal fail the guard and vanish from the UI without a trace.
  return typeof d.suggestedCreatorCount === 'number';
}

/** A `calculate_budget` result that actually carries figures — see {@link isQuotedBudget}. */
export type QuotedBudgetPayload = CalculateBudgetPayload & {
  suggestedPoolTotal: number;
  suggestedPerCreatorRate: number;
};

/**
 * True only when `calculate_budget` actually quoted — i.e. a real niche rate band backed it.
 * Callers MUST branch on this before rendering any currency: on the other path there is no
 * number, and inventing one (a zero, a placeholder, a percentage of anything) would recreate the
 * P1-12 defect in the frontend after it was removed from the backend.
 *
 * Both legs are checked on purpose. `rateBasis` is the intent; the `typeof` checks are what make
 * this a sound type predicate, and they also hold the line if an older backend (no `rateBasis` on
 * the wire at all) is ever talking to this frontend.
 */
export function isQuotedBudget(payload: CalculateBudgetPayload): payload is QuotedBudgetPayload {
  return (
    payload.rateBasis !== 'insufficient_data' &&
    typeof payload.suggestedPerCreatorRate === 'number' &&
    typeof payload.suggestedPoolTotal === 'number'
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
// Creator tool payloads — Phase B0 "Paste and Read" (T-MEERA-CREATOR-PHASE-B
// SPEC.md §8.2, B0-16). Snake_case, mirroring `CreatorToolDtos` (§3.5)
// field-for-field — creator payloads follow `MeeraContextDtos`, NOT the
// camelCase `MeeraToolDtos` payloads above. Six B0 tools only: get_my_deals,
// get_brief, estimate_my_rate, get_my_metrics, check_deal_risks, draft_reply.
// SendRoutineReplyPayload/RankOpenCampaignsPayload/DraftApplicationPayload
// (send_routine_reply/rank_open_campaigns/draft_application) are Phase
// B1/B7 tools and are deliberately NOT declared here.
// ---------------------------------------------------------------------------

/** `CreatorToolDtos.DealSummary` (§3.5) — one row of `GetMyDealsPayload.deals`. */
export interface DealSummary {
  deal_id: string;
  brand_name: string;
  campaign_title: string;
  status: string;
  status_label: string;
  /** "8,000" — omitted (NON_NULL) when there is no amount yet. */
  amount?: string;
  /** Same nullable pair as `amount` (both come from the one `amountValue` BigDecimal on the Java
   *  side) — omitted (NON_NULL) whenever `amount` is. */
  amount_value?: number;
  currency: string;
  /** e.g. "reply to brand" — omitted when there is nothing to do. */
  next_action?: string;
  next_deadline?: string;
  secured: boolean;
  unread_count: number;
  has_pending_offer: boolean;
  /** The PLATFORM `CreatorBrief` for this collaboration, if one exists. */
  brief_id?: string;
}

/** `CreatorToolDtos.GetMyDealsResult` (§3.5) — `get_my_deals` tool result. */
export interface GetMyDealsPayload {
  deals: DealSummary[];
  active_count: number;
  completed_count: number;
}

/**
 * `CreatorToolDtos.GetBriefResult` (§3.5) — `get_brief` tool result. `@JsonInclude(NON_NULL)`
 * (`CreatorToolDtos.java` ~129-138): a field the backend has nothing for is OMITTED, not sent as
 * null (F6, KAVYA-FE-RECHECK-0917.md).
 *   - `extraction`/`flags` are guaranteed present on a successful read — `GetBriefExecutor`
 *     refuses with 409 otherwise (RULINGS-U-0917.md Addition A) — so both stay required.
 *   - `quote` is NOT guaranteed: `CreatorBriefService`'s `readJson(brief.getQuoteJson(),
 *     PackageQuote.class)` yields null, and the key is omitted, for an unset/unreadable quote
 *     column. A real, successfully-read brief can have no stored price.
 *   - `extraction_source` is omitted on a row with no recorded extraction source.
 */
export interface GetBriefPayload {
  brief_id: string;
  source: 'PASTED' | 'PLATFORM';
  status: 'NEW' | 'ANALYZED' | 'DRAFTED' | 'SECURED' | 'DISMISSED';
  /** Only set for a PLATFORM brief (source of the collaboration it was built from). */
  deal_id?: string;
  extraction: BriefExtraction;
  flags: RiskFlag[];
  quote?: PackageQuote;
  extraction_source?: 'AI' | 'FALLBACK';
}

/** `CreatorToolDtos.EstimateMyRateResult` (§3.5) — `estimate_my_rate` tool result. */
export interface EstimateMyRatePayload {
  quote: PackageQuote;
}

/** `CreatorToolDtos.MetricsResult` (§3.5) — nested metrics on `GetMyMetricsPayload`. Per
 *  `GetMyMetricsExecutor` (§3.6): when `connected` is false every string field below is omitted
 *  except `tier`, and `data_source` (only set to `SELF_REPORTED` when the profile has followers). */
export interface MetricsResult {
  connected: boolean;
  followers?: string;
  reach_30d?: string;
  engagement_rate?: string;
  avg_reach_per_post?: string;
  verified_at?: string;
  data_source?: string;
  tier?: string;
  quality_score?: string;
}

/** `CreatorToolDtos.GetMyMetricsResult` (§3.5) — `get_my_metrics` tool result. */
export interface GetMyMetricsPayload {
  metrics: MetricsResult;
}

/**
 * `CreatorToolDtos.CheckDealRisksResult` (§3.5) — `check_deal_risks` tool result. This is the
 * exact same Java record as `api.ts`'s `DealRisksResponse` (the `GET /deals/:id/risks` HTTP
 * response); aliased rather than redeclared so the two stay in sync by construction.
 */
export type CheckDealRisksPayload = DealRisksResponse;

/**
 * `CreatorToolDtos.DraftReplyResult` (§3.5) — `draft_reply` tool result. `kind` is narrowed to
 * the three values `DraftReplyExecutor` can actually produce (§3.6) — `DraftItem.kind` in
 * `api.ts` carries the full five-value `DraftKind` enum for the drafts list instead.
 * `proposed_amount`/`proposed_amount_value`/`deal_terms` are COUNTER-only; `target_deal_id`/
 * `target_brief_id` are mutually exclusive (one of `deal_id`/`brief_id` was supplied as input);
 * `withheld_reason` is only set when `withheld` is true.
 */
export interface DraftReplyPayload {
  draft_id: string;
  kind: 'REPLY' | 'COUNTER' | 'DECLINE';
  text: string;
  proposed_amount?: string;
  proposed_amount_value?: number;
  deal_terms?: DealTerms;
  target_deal_id?: string;
  target_brief_id?: string;
  withheld: boolean;
  withheld_reason?: string;
}

export function isGetMyDealsPayload(data: unknown): data is GetMyDealsPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<GetMyDealsPayload>;
  return Array.isArray(d.deals);
}

const GET_BRIEF_STATUSES = new Set(['NEW', 'ANALYZED', 'DRAFTED', 'SECURED', 'DISMISSED']);
const BRIEF_SOURCES = new Set(['PASTED', 'PLATFORM']);
const EXTRACTION_SOURCES = new Set(['AI', 'FALLBACK']);

/**
 * The original version of this guard only checked `brief_id` and `extraction`, which let a
 * payload with no `flags`/`quote`/`status`/`extraction_source` through as "valid" — a `get_brief`
 * card built on that admitted object rendered nonsense straight from the missing fields (a quote
 * card reading "Not available yet · undefined revisions · Based on ."). Every field the card
 * actually reads is checked here, so a malformed payload renders nothing instead.
 *
 * F6 fix round 2 (KAVYA-FE-RECHECK-0917.md) — the FIRST fix over-corrected: requiring `quote`
 * and `extraction_source` rejected a real, successful, price-less brief outright (the same F6
 * failure, from the opposite direction — see `GetBriefPayload`'s own doc comment for why neither
 * is guaranteed). Both are optional here: ABSENT passes, but a PRESENT value that is not the
 * right shape (a `quote` that is not an object, or an `extraction_source` that is not one of the
 * two known values) still fails the guard rather than being coerced or ignored.
 */
export function isGetBriefPayload(data: unknown): data is GetBriefPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<GetBriefPayload>;
  const quoteOk =
    d.quote === undefined ||
    (typeof d.quote === 'object' && d.quote !== null && !Array.isArray(d.quote));
  const extractionSourceOk =
    d.extraction_source === undefined ||
    (typeof d.extraction_source === 'string' && EXTRACTION_SOURCES.has(d.extraction_source));
  return (
    typeof d.brief_id === 'string' &&
    typeof d.status === 'string' &&
    GET_BRIEF_STATUSES.has(d.status) &&
    typeof d.source === 'string' &&
    BRIEF_SOURCES.has(d.source) &&
    !!d.extraction &&
    typeof d.extraction === 'object' &&
    Array.isArray(d.flags) &&
    quoteOk &&
    extractionSourceOk
  );
}

/**
 * Defence in depth ONLY (KAVYA-FE-RECHECK-0917.md) — `GetBriefExecutor` now refuses a NEW or
 * incomplete brief with a 409 before this ever reaches the client (RULINGS-U-0917.md Addition A),
 * so a bare `{ brief_id, status: 'NEW' }` stub with no `extraction`/`flags` at all should never
 * actually arrive here; `isGetBriefPayload` correctly rejects it since those two stay required.
 * If one ever did anyway (a stale build, a future regression), the renderer falls back to this
 * check so it still reads as "still reading this brief" instead of silently rendering nothing.
 */
export function isNewBriefStub(data: unknown): data is { brief_id: string; status: 'NEW' } {
  if (!data || typeof data !== 'object') return false;
  const d = data as { brief_id?: unknown; status?: unknown };
  return typeof d.brief_id === 'string' && d.status === 'NEW';
}

export function isEstimateMyRatePayload(data: unknown): data is EstimateMyRatePayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<EstimateMyRatePayload>;
  return !!d.quote && typeof d.quote === 'object';
}

export function isGetMyMetricsPayload(data: unknown): data is GetMyMetricsPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<GetMyMetricsPayload>;
  return !!d.metrics && typeof d.metrics === 'object';
}

export function isCheckDealRisksPayload(data: unknown): data is CheckDealRisksPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<CheckDealRisksPayload>;
  return Array.isArray(d.flags);
}

export function isDraftReplyPayload(data: unknown): data is DraftReplyPayload {
  if (!data || typeof data !== 'object') return false;
  const d = data as Partial<DraftReplyPayload>;
  return typeof d.draft_id === 'string' && typeof d.kind === 'string';
}

/** The six Phase B0 creator tools (SPEC.md §1 scope table, §8.2). Phase B1/B7 add
 *  send_routine_reply, rank_open_campaigns and draft_application to this set later. */
export const CREATOR_TOOL_NAMES = [
  'get_my_deals',
  'get_brief',
  'estimate_my_rate',
  'get_my_metrics',
  'check_deal_risks',
  'draft_reply',
  // T-CONTENT-TOPICS / T-PLAN-MY-WEEK. A name missing here is dropped by `isCreatorToolName`,
  // so the chat shows NOTHING for that tool call - no card, and no step in the work trail,
  // while Meera was in fact reading the topics or planning the week. Kept in step with
  // influora-ai's own CREATOR_TOOL_NAMES by meera-api.creator-tools-in-sync.test.ts.
  'get_todays_topics',
  'plan_my_week',
] as const;

export type CreatorToolName = (typeof CREATOR_TOOL_NAMES)[number];

export function isCreatorToolName(name: string): name is CreatorToolName {
  return (CREATOR_TOOL_NAMES as readonly string[]).includes(name);
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

/**
 * T-MEERA-CREATOR-PHASE-A (A10) — Meera is no longer brand-only. `role` defaults to 'brand' so
 * every pre-existing call site (which never passed one) keeps reading `brand_token` exactly as
 * before; the creator chat entry (CreatorMeeraChatPanel) is the only caller that passes
 * 'creator'. Matches the `brand_token`/`creator_token` split `src/lib/api.ts`'s `HttpClient`
 * already uses — deliberately NOT a brand-then-creator fallback, which would silently attach
 * the wrong role's token when a browser happens to hold both (a real case during QA/dev).
 */
export type MeeraRole = 'brand' | 'creator';

function getToken(role: MeeraRole = 'brand'): string | null {
  // F-0551 SHIP-BLOCKER, caught by a fresh-context review before this shipped. When the access
  // token moved to memory-only in live mode, `brand_token`/`creator_token` stopped holding a
  // credential — they now hold the inert LIVE_SESSION_TOKEN_HINT sentinel ('session-active'),
  // kept only so two out-of-scope hooks that gate on the key's PRESENCE keep working. Reading
  // that key here sent `Authorization: Bearer session-active` on every Meera REST call and the
  // voice endpoints, i.e. the entire Meera surface would have gone anonymous in live mode.
  //
  // This is the documented "two API layers" trap in this repo: api.ts and meera-api.ts each keep
  // their own auth accessor, so a change made in one silently misses the other. The memory store
  // lives in auth-session.ts precisely so BOTH layers can share it.
  const inMemory = getMemoryAccessToken(role);
  if (inMemory) return inMemory;
  // Mock/demo mode still keeps its (non-credential) token in localStorage — see the
  // memoryAccessTokens comment in auth-session.ts for why mock mode is deliberately untouched.
  const stored = localStorage.getItem(role === 'creator' ? 'creator_token' : 'brand_token');
  return stored === LIVE_SESSION_TOKEN_HINT ? null : stored;
}

/**
 * T-MEERA-CREATOR-PHASE-A (fix round 1, item 1) — `role` used to select only the auth token,
 * not the URL: every call below hit the brand-gated `/meera/...` path regardless of role, so a
 * creator session 403'd at `BrandContextService.requireBrand` before ever reaching
 * `CreatorMeeraController` (`/creator/meera/...`), which exists and is the correct counterpart.
 * This is now the single place that maps role -> path prefix; every method below must route
 * through it rather than hardcoding `/meera`.
 */
function basePath(role: MeeraRole): string {
  return role === 'creator' ? '/creator/meera' : '/meera';
}

async function request<T>(
  method: 'GET' | 'POST',
  path: string,
  opts: {
    body?: unknown;
    idempotencyKey?: string;
    query?: Record<string, string | number | undefined>;
    role?: MeeraRole;
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
  const token = getToken(opts.role);
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
  startSession: async (role: MeeraRole = 'brand'): Promise<MeeraSessionResponse> => {
    if (!isApiLive()) {
      await delay();
      return {
        conversationId: 'mock_conv_001',
        status: 'ACTIVE',
        brandProfileStatus: 'READY',
        credits: { remaining: 100, unlimited: false },
      };
    }
    return request<MeeraSessionResponse>('POST', `${basePath(role)}/sessions`, { role });
  },

  /**
   * POST /meera/sessions/{conversationId}/messages - Send a turn
   * Returns streamToken + streamUrl for SSE connection
   */
  sendTurn: async (
    conversationId: string,
    content: string,
    role: MeeraRole = 'brand'
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
    return request<MeeraTurnResponse>(
      'POST',
      `${basePath(role)}/sessions/${conversationId}/messages`,
      {
        body: { content },
        idempotencyKey: safeRandomUUID(),
        role,
      }
    );
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
    // This is the SECOND route to POST /wallet/escrow/fund — `api.payments.fundEscrow` in api.ts
    // is the first, and `useEscrowFund` reaches the endpoint through here rather than there.
    // Guarding only api.ts would have left this path open, so the same preemptive check lives on
    // both. See `PAYMENTS_ENABLED` in api.ts for why the request must never be issued at all.
    if (isMoneyActionBlocked('escrow-fund')) {
      throw new PaymentsUnavailableError('escrow-fund');
    }
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
    conversationId: string,
    role: MeeraRole = 'brand'
  ): Promise<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>> => {
    if (!isApiLive()) return [];
    return request<Array<{ id: string; role: 'USER' | 'ASSISTANT'; content: string }>>(
      'GET',
      `${basePath(role)}/sessions/${conversationId}/messages`,
      { role }
    );
  },

  /**
   * GET /meera/sessions/{conversationId}/messages?after={messageId}
   * Non-streaming fallback for fetching finalized turn
   */
  getMessagesAfter: async (
    conversationId: string,
    afterMessageId: string,
    role: MeeraRole = 'brand'
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
      `${basePath(role)}/sessions/${conversationId}/messages`,
      { query: { after: afterMessageId }, role }
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
  speak: async (text: string, lang?: string, role: MeeraRole = 'brand'): Promise<Blob | null> => {
    if (!isApiLive()) return null;

    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      const token = getToken(role);
      if (token) headers.Authorization = `Bearer ${token}`;

      // T-MEERA-CREATOR-PHASE-A gate review fix round 2 — Vikram's CreatorMeeraController now
      // exposes /creator/meera/voice/speak with the same request/response shape as the brand
      // route, so this routes through the same role -> path-prefix mapping every other Meera
      // call uses (basePath) instead of the brand-only /meera/voice/speak this used to hardcode.
      const res = await fetch(`${API_BASE_URL}${basePath(role)}/voice/speak`, {
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
  transcribe: async (audio: Blob, role: MeeraRole = 'brand'): Promise<MeeraTranscribeResult | null> => {
    if (!isApiLive()) return null;

    try {
      const headers: Record<string, string> = {};
      const token = getToken(role);
      if (token) headers.Authorization = `Bearer ${token}`;

      const formData = new FormData();
      // Field name is contractual — the backend reads the multipart part
      // named `audio`. No workspace_id in the body; the server derives it
      // from the auth token, same as every other /meera/* call.
      formData.append('audio', audio);

      // T-MEERA-CREATOR-PHASE-A gate review fix round 2 — same routing fix as `speak()` above:
      // CreatorMeeraController now exposes /creator/meera/voice/transcribe, so this goes through
      // basePath(role) instead of the brand-only /meera/voice/transcribe it used to hardcode.
      const res = await fetch(`${API_BASE_URL}${basePath(role)}/voice/transcribe`, {
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

  /**
   * POST /creator/meera/shoot-check/frame — Level 2 "Check my frame" for the creator Shoot Check
   * screen. Multipart: `image` (a single JPEG still, downscaled client-side to max 800px wide at
   * ~0.7 quality by the caller before this ever runs — this method does no image processing
   * itself) and an optional `shot_label`.
   *
   * Goes through `basePath(role)` like every other method here. It used to post to a flat
   * `/ai/shoot-check/frame`, which is influora-ai's own route: this app cannot reach that service
   * directly, so every tap failed in production. `CreatorMeeraController#checkFrame` is the proxy,
   * and `meera-api.shoot-check-route.test.ts` pins this URL to that Java mapping. The creator's
   * identity comes from the auth token on the server, so no workspace id is sent.
   *
   * Same one-thing-to-check discipline as `transcribe()`: returns `null` for every "no result"
   * case — mock mode, the endpoint not existing yet (404) or any other non-2xx, an unparsable
   * body, or a network error — never throws. `ShootCheckPanel` renders `null` as a plain "couldn't
   * check your frame right now" line and does not retry automatically.
   */
  checkFrame: async (
    image: Blob,
    shotLabel: string | undefined,
    role: MeeraRole = 'creator'
  ): Promise<MeeraShootCheckFrameResult | null> => {
    if (!isApiLive()) {
      await delay(600);
      return {
        fixes: ['Move a little closer — your face is small in the frame'],
        settings: ['Turn on grid lines in your camera app to help with framing'],
        ok: ['Lighting looks even'],
      };
    }

    try {
      const headers: Record<string, string> = {};
      const token = getToken(role);
      if (token) headers.Authorization = `Bearer ${token}`;

      const formData = new FormData();
      formData.append('image', image, 'frame.jpg');
      if (shotLabel) formData.append('shot_label', shotLabel);

      const res = await fetch(`${API_BASE_URL}${basePath(role)}/shoot-check/frame`, {
        method: 'POST',
        headers,
        credentials: 'include',
        body: formData,
      });

      if (!res.ok) return null;

      let body: { fixes?: unknown; settings?: unknown; ok?: unknown };
      try {
        body = await res.json();
      } catch {
        return null;
      }

      const asStringArray = (value: unknown): string[] =>
        Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];

      return {
        fixes: asStringArray(body.fixes),
        settings: asStringArray(body.settings),
        ok: asStringArray(body.ok),
      };
    } catch {
      return null;
    }
  },

  /**
   * POST /workspaces/{workspaceId}/meera/interactions/option-tapped — ME-1
   * (BrandF.md §115) fix: this endpoint (MeeraInteractionController.java)
   * existed with zero FE callers. Fire-and-forget flywheel telemetry for the
   * `present_options` tappable-card pattern; never blocks or surfaces errors
   * to the brand — a dropped analytics event isn't worth interrupting the
   * chat turn that's already in flight (ToolResultRenderer.tsx's onOptionPick).
   *
   * The `{workspaceId}` path segment is deliberately ignored server-side —
   * the controller resolves the workspace from the authenticated principal
   * only (IDOR fix, see its class javadoc) — so `'me'` is used here the same
   * way `/workspaces/me` already does for the self-scoped workspace routes
   * in src/lib/api.ts.
   */
  logOptionTapped: (sessionId: string, toolName: string, recommended?: boolean): void => {
    if (!isApiLive()) return;
    request<void>('POST', '/workspaces/me/meera/interactions/option-tapped', {
      body: { sessionId, toolName, recommended: recommended ?? null },
    }).catch(() => {
      // Telemetry only — swallow failures.
    });
  },
};

export default meeraApi;
