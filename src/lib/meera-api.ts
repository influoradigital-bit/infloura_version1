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
  /**
   * Review finding #6 — the server's `TurnResult.creditsRemaining` is a Java
   * `Integer` (nullable): `CreatorMeeraController` coerces it to the literal
   * `0` before sending today when creator credits are flag-disabled, so the
   * wire currently always carries a number. `number | null` (not `number`)
   * means a future server-side regression that starts sending a literal
   * `null` under the flag is a type error here instead of an unnoticed
   * runtime `NaN`/`undefined` downstream.
   */
  creditsRemaining: number | null;
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
 * the creator Shoot Check screen (`ShootCheckPanel.tsx`). The coach shape (2026-09-25): what the
 * photo shows, ordered steps each naming its knowledge entry, what one photo cannot tell, and at
 * most one coach-bank question. The legacy lists `fixes` (non-settings step texts), `settings`
 * (settings step texts) and `ok` (what's already fine) are still sent, so a response from an older
 * server — which sends ONLY those — still renders as the old Fix/Settings/Looking good groups.
 */
export interface MeeraShootCheckFrameResult {
  fixes: string[];
  settings: string[];
  ok: string[];
  /** One line on what the photo shows, as the creator would recognise it. `null` from an older
   *  server that only sends the three legacy lists. */
  whatISee: string | null;
  /** The ordered steps, each from one named knowledge entry. The server keeps only steps whose
   *  `note` names a real shooting entry and whose numbers appear in that entry, and sorts them
   *  move_you, move_phone, move_light, settings; this client re-sorts defensively. Empty from an
   *  older server — the panel then shows the legacy `fixes`/`settings` lists instead. */
  steps: MeeraShootCheckStep[];
  /** Up to 3 things one photo cannot show that matter for this shot. */
  cantTell: string[];
  /** One coach-bank question whose answer would change the steps, or `null`. */
  ask: MeeraShootCheckAsk | null;
  /** The language the server wrote the reply in ('hi' is Hinglish in Latin script). The panel
   *  shows every heading, chip and caption in this language, so a Hinglish reply never sits under
   *  English headings. `null` from an older server that does not say; the panel then uses the
   *  creator's own language setting. */
  lang: 'en' | 'hi' | null;
  /** `true` when the photo could not be judged (too dark, lens covered, blank, too blurry,
   *  unclear): `whatISee` is then the one "please retake it" line and there are no steps. From an
   *  older server that does not send it, inferred from that same shape (a `whatISee` line and
   *  nothing else). */
  retake: boolean;
}

export type MeeraShootCheckStepKind = 'move_you' | 'move_phone' | 'move_light' | 'settings';

/** The order the server sorts steps in: move yourself, then the phone, then the light, then the
 *  phone's settings. */
export const SHOOT_CHECK_STEP_ORDER: readonly MeeraShootCheckStepKind[] = ['move_you', 'move_phone', 'move_light', 'settings'];

export interface MeeraShootCheckStep {
  kind: MeeraShootCheckStepKind;
  text: string;
  /** Exact name of the Influora knowledge entry this step comes from. Kept for older clients; the
   *  panel shows `label` instead. */
  note: string;
  /** A short topic label for the step in the reply's language ("Window behind you"), shown as
   *  "From the guide: ...". Falls back to `note` when an older server sends none. */
  label: string;
  /** Only on a step from a phone-settings entry: the same settings `text` lists, one per part, in
   *  the same order, so the panel can show them as a label/value list. `needsPro` marks the parts
   *  that `text` puts under "If your camera app has a Pro video mode:", `needsOis` those under
   *  "If your phone has optical stabilisation (OIS):" (sent when no saved phone says it has OIS).
   *  Absent on every other step and from an older server; the panel then shows `text`. */
  parts?: MeeraShootCheckStepPart[];
}

export interface MeeraShootCheckStepPart {
  label: string;
  value: string;
  needsPro: boolean;
  /** `true` only for a part that holds just if the phone has optical stabilisation (never together
   *  with `needsPro`); absent otherwise. The panel must not show such a part as plain fact. */
  needsOis?: boolean;
}

/** A coach question from the bank (`coach_question` rows in influora-ai's knowledge), already
 *  replaced server-side with its canonical text. `questionHi`/`hi` are Hinglish in Latin script. */
export interface MeeraShootCheckAsk {
  id: string;
  questionEn: string;
  questionHi: string;
  options: Array<{ en: string; hi: string }>;
}

/** One answer the creator tapped: the coach question id and the 0-based option index. */
export interface MeeraCoachAnswer {
  id: string;
  option: number;
}

/** The planned shot, sent as `shot_context` so the check knows what the creator is trying to film.
 *  Creator/model-written text, so influora-ai wraps it as untrusted. */
export interface MeeraShotContext {
  angle?: string;
  action?: string;
  prop?: string;
  where?: string;
  light?: string;
  on_camera?: string;
  sit_or_walk?: string;
  /** The planned beat and set-up line. */
  line?: string;
}

/** Priority order: when the JSON does not fit, keys are dropped from the END of this list.
 *  `on_camera` and `sit_or_walk` sit right after `line` (Ash review 2026-09-25, item 10): they are
 *  the only keys that settle a coach question server-side (influora-ai frame_check.py
 *  `answered_question_ids`), and they are a few words each, so they must never be the first to
 *  go when a long `line`/`where` fills the budget. */
const SHOT_CONTEXT_KEYS: ReadonlyArray<keyof MeeraShotContext> = [
  'line',
  'on_camera',
  'sit_or_walk',
  'angle',
  'action',
  'where',
  'light',
  'prop',
];

/** Server limits for the two optional text parts (the Java proxy answers a longer value with 400
 *  `FIELD_TOO_LONG`, which would turn the whole check into "couldn't check"). */
export const SHOT_CONTEXT_MAX_CHARS = 1000;
export const ANSWERS_MAX_CHARS = 600;
export const ANSWERS_MAX_ITEMS = 3;

/**
 * `shot_context` as a JSON object string that always fits `SHOT_CONTEXT_MAX_CHARS`: known keys
 * only, blank values dropped, whitespace collapsed, each value clipped to 300 characters, and — if
 * it still does not fit — the lowest-priority keys dropped. `null` when nothing is left to send.
 */
export function serializeShotContext(context: MeeraShotContext | undefined): string | null {
  if (!context) return null;
  const entries: Array<[string, string]> = [];
  for (const key of SHOT_CONTEXT_KEYS) {
    const value = context[key];
    if (typeof value !== 'string') continue;
    const clean = value.trim().replace(/\s+/g, ' ');
    if (clean) entries.push([key, clean.slice(0, 300)]);
  }
  while (entries.length > 0) {
    const json = JSON.stringify(Object.fromEntries(entries));
    if (json.length <= SHOT_CONTEXT_MAX_CHARS) return json;
    entries.pop();
  }
  return null;
}

/** `answers` as a JSON array string: well-formed items only, the latest answer per id kept, at
 *  most `ANSWERS_MAX_ITEMS` (the most recent ones). `null` when there is nothing to send. */
export function serializeCoachAnswers(answers: MeeraCoachAnswer[] | undefined): string | null {
  if (!answers || answers.length === 0) return null;
  const byId = new Map<string, number>();
  for (const answer of answers) {
    if (!answer || typeof answer.id !== 'string' || !answer.id.trim()) continue;
    if (!Number.isInteger(answer.option) || answer.option < 0) continue;
    byId.delete(answer.id);
    byId.set(answer.id, answer.option);
  }
  const items = [...byId.entries()].slice(-ANSWERS_MAX_ITEMS).map(([id, option]) => ({ id, option }));
  while (items.length > 0) {
    const json = JSON.stringify(items);
    if (json.length <= ANSWERS_MAX_CHARS) return json;
    items.shift();
  }
  return null;
}

function parseShootCheckSteps(value: unknown): MeeraShootCheckStep[] {
  if (!Array.isArray(value)) return [];
  const steps: Array<{ step: MeeraShootCheckStep; order: number; i: number }> = [];
  value.forEach((raw: unknown, i) => {
    if (!raw || typeof raw !== 'object') return;
    const { kind, text, note, label, parts } = raw as {
      kind?: unknown;
      text?: unknown;
      note?: unknown;
      label?: unknown;
      parts?: unknown;
    };
    const order = typeof kind === 'string' ? (SHOOT_CHECK_STEP_ORDER as readonly string[]).indexOf(kind) : -1;
    if (order === -1) return;
    if (typeof text !== 'string' || !text.trim()) return;
    const cleanNote = typeof note === 'string' ? note.trim() : '';
    const step: MeeraShootCheckStep = {
      kind: SHOOT_CHECK_STEP_ORDER[order],
      text: text.trim(),
      note: cleanNote,
      label: typeof label === 'string' && label.trim() ? label.trim() : cleanNote,
    };
    // Only a settings step carries `parts`; on any other kind they would hide the step's text.
    const parsedParts = step.kind === 'settings' ? parseShootCheckStepParts(parts) : [];
    if (parsedParts.length > 0) step.parts = parsedParts;
    steps.push({ step, order, i });
  });
  // Stable sort by the server's own order, in case an older or different server did not sort.
  return steps
    .sort((a, b) => a.order - b.order || a.i - b.i)
    .map(({ step }) => step)
    .slice(0, 5);
}

/** A settings step's `parts`: `needs_pro` and `needs_ois` count only when exactly `true` (a Pro
 *  part is never also an OIS part). If ANY item lacks a text label and value the whole list is
 *  dropped (never thrown on), so a partly broken list can never show less than the step's text.
 *  An empty result means "show the text". */
function parseShootCheckStepParts(value: unknown): MeeraShootCheckStepPart[] {
  if (!Array.isArray(value)) return [];
  const parts: MeeraShootCheckStepPart[] = [];
  for (const raw of value as unknown[]) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return [];
    const {
      label,
      value: partValue,
      needs_pro,
      needs_ois,
    } = raw as { label?: unknown; value?: unknown; needs_pro?: unknown; needs_ois?: unknown };
    if (typeof label !== 'string' || !label.trim()) return [];
    if (typeof partValue !== 'string' || !partValue.trim()) return [];
    const part: MeeraShootCheckStepPart = { label: label.trim(), value: partValue.trim(), needsPro: needs_pro === true };
    if (needs_ois === true && !part.needsPro) part.needsOis = true;
    parts.push(part);
  }
  return parts;
}

function parseShootCheckAsk(value: unknown): MeeraShootCheckAsk | null {
  if (!value || typeof value !== 'object') return null;
  const { id, question_en, question_hi, options } = value as {
    id?: unknown;
    question_en?: unknown;
    question_hi?: unknown;
    options?: unknown;
  };
  if (typeof id !== 'string' || !id.trim()) return null;
  if (typeof question_en !== 'string' || !question_en.trim()) return null;
  if (!Array.isArray(options)) return null;
  const parsed: Array<{ en: string; hi: string }> = [];
  for (const option of options as unknown[]) {
    if (!option || typeof option !== 'object') return null;
    const { en, hi } = option as { en?: unknown; hi?: unknown };
    if (typeof en !== 'string' || !en.trim()) return null;
    parsed.push({ en: en.trim(), hi: typeof hi === 'string' && hi.trim() ? hi.trim() : en.trim() });
  }
  // The bank has 2-4 options per question; anything else is not a question a tap can answer.
  if (parsed.length < 2 || parsed.length > 4) return null;
  return {
    id: id.trim(),
    questionEn: question_en.trim(),
    questionHi: typeof question_hi === 'string' && question_hi.trim() ? question_hi.trim() : question_en.trim(),
    options: parsed,
  };
}

/** Reads a (non-fallback) `/shoot-check/frame` body: the coach fields plus the legacy lists. */
export function parseShootCheckFrameBody(body: Record<string, unknown>): MeeraShootCheckFrameResult {
  const asStringArray = (value: unknown, max?: number): string[] => {
    const list = Array.isArray(value)
      ? value.filter((v): v is string => typeof v === 'string' && v.trim().length > 0).map((v) => v.trim())
      : [];
    return max === undefined ? list : list.slice(0, max);
  };
  const whatISee = typeof body.what_i_see === 'string' && body.what_i_see.trim() ? body.what_i_see.trim() : null;
  const ok = asStringArray(body.ok);
  const steps = parseShootCheckSteps(body.steps);
  const cantTell = asStringArray(body.cant_tell, 3);
  const ask = parseShootCheckAsk(body.ask);
  const lang = body.lang === 'hi' || body.lang === 'en' ? body.lang : null;
  // A server that says `retake` is believed; an older one that does not is read by the shape it
  // gives an unusable photo: the one what_i_see line and nothing else.
  const retake =
    typeof body.retake === 'boolean'
      ? body.retake
      : whatISee !== null && steps.length === 0 && ok.length === 0 && cantTell.length === 0 && ask === null;
  return {
    fixes: asStringArray(body.fixes),
    settings: asStringArray(body.settings),
    ok,
    whatISee,
    steps,
    cantTell,
    ask,
    lang,
    retake,
  };
}

/**
 * The `code` influora-ai's `/ai/shoot-check/frame` route puts on its 200 `fallback: true` envelope
 * when the creator's monthly Meera usage cap has been reached (`app/costs/spend_tracker.py`
 * `CREATOR_CAP_CODE`, read off that source rather than retyped) — the ONE fallback body the panel
 * shows a cap-specific line for instead of the generic "couldn't check" message.
 */
export const SHOOT_CHECK_CREATOR_CAP_CODE = 'CREATOR_MONTHLY_CAP_REACHED';

/**
 * `checkFrame`'s result. influora-ai returns HTTP 200 with `fallback: true` for EVERY failure and
 * gate-block path (a bad upload, an oversize photo, the daily spend ceiling, a provider error,
 * malformed model output, the creator's monthly cap, …) — `shoot_check.py`'s own `fallback_response()`
 * always fills `fixes` with placeholder text on that path (its own generic line, or an upload
 * instruction like "please upload one of those and try again"), NEVER a real per-photo check
 * result. Treating that as `fixes` showed a "Fix" card with an upload instruction on a screen with
 * no upload button, and the panel's own "couldn't check your frame right now" line never appeared.
 * A `fallback: true` body is therefore always `'unavailable'` (or `'capped'`, the one case with its
 * own message) here — its `fixes`/`settings`/`ok` are never read as a real result.
 */
export type MeeraShootCheckFrameOutcome =
  | { kind: 'ok'; result: MeeraShootCheckFrameResult; chat?: MeeraPhotoCheckChat }
  | { kind: 'capped'; message: string }
  | { kind: 'unavailable' };

/**
 * Photo check inside Meera's chat (SPEC section 2b): on a real (`fallback: false`) result Spring
 * adds `chat: {text, message_id, user_message_id}` to the body. `text` is the compact summary
 * Spring built from influora-ai's JSON (`PhotoCheckSummary`) — the chat keeps it as the message's
 * `text`, so later turns replay it to the model exactly as it was stored. The two ids are the
 * USER and ASSISTANT rows Spring saved into the conversation; both are `null` when no
 * `conversation_id` was sent or the write failed (the card still shows, it just will not reload).
 * Absent from an older server, and from every fallback body.
 */
export interface MeeraPhotoCheckChat {
  text: string;
  messageId: string | null;
  userMessageId: string | null;
}

/** Server limit for `user_line` (CreatorMeeraController answers a longer one with 400
 *  FIELD_TOO_LONG, which would turn the whole check into "couldn't check"). Code points, not
 *  UTF-16 units, to match Java's `codePointCount`. */
export const PHOTO_CHECK_USER_LINE_MAX = 200;

/** `?before=` page size on `GET /creator/meera/sessions/{id}/messages` (SPEC section 3b). */
export const MEERA_HISTORY_PAGE = 50;

/**
 * The persisted photo-check card on a history item (SPEC section 2d). Spring sets `card` only for
 * an ASSISTANT row whose metadata says `kind: "photo_check"`, and only these four keys. The card
 * is ALWAYS rebuilt from here — never parsed out of the message text — so a model reply that
 * imitates the summary's format can never turn into a card.
 */
export interface MeeraHistoryCard {
  kind: 'photo_check';
  v: 1;
  result: Record<string, unknown>;
  shot_label?: string;
}

/** One stored message from `GET .../sessions/{id}/messages`. `card` is creator-only and absent
 *  (`@JsonInclude(NON_NULL)`) on every ordinary row and on the whole brand wire. */
export interface MeeraHistoryItem {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  card?: MeeraHistoryCard;
}

function parsePhotoCheckChat(value: unknown): MeeraPhotoCheckChat | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const { text, message_id, user_message_id } = value as {
    text?: unknown;
    message_id?: unknown;
    user_message_id?: unknown;
  };
  // Kept byte-for-byte (not trimmed): it is what Spring stored and what reload will show.
  if (typeof text !== 'string' || !text.trim()) return undefined;
  const id = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v : null);
  return { text, messageId: id(message_id), userMessageId: id(user_message_id) };
}

/**
 * A history item's `card` as a photo-check result, or `null` when it is not a usable photo-check
 * card: missing, another kind or version, a `result` that is not an object, a fallback body, or a
 * result with nothing to show. The ONE way the chat turns a stored row back into a card
 * (`parseShootCheckFrameBody` is the one body parser).
 */
export function photoCheckFromHistoryCard(
  card: unknown,
): { result: MeeraShootCheckFrameResult; shotLabel?: string } | null {
  if (!card || typeof card !== 'object' || Array.isArray(card)) return null;
  const { kind, v, result, shot_label } = card as {
    kind?: unknown;
    v?: unknown;
    result?: unknown;
    shot_label?: unknown;
  };
  if (kind !== 'photo_check' || v !== 1) return null;
  if (!result || typeof result !== 'object' || Array.isArray(result)) return null;
  const body = result as Record<string, unknown>;
  if (body.fallback === true) return null;
  const parsed = parseShootCheckFrameBody(body);
  const hasContent =
    parsed.whatISee !== null ||
    parsed.steps.length > 0 ||
    parsed.fixes.length > 0 ||
    parsed.settings.length > 0 ||
    parsed.ok.length > 0 ||
    parsed.cantTell.length > 0 ||
    parsed.ask !== null;
  if (!hasContent) return null;
  const label = typeof shot_label === 'string' && shot_label.trim() ? shot_label.trim() : undefined;
  return label ? { result: parsed, shotLabel: label } : { result: parsed };
}

/** One line, trimmed, at most `max` code points (never splits a surrogate pair). */
function clipLine(text: string, max: number): string {
  const one = text.replace(/\s+/g, ' ').trim();
  const points = Array.from(one);
  return points.length <= max ? one : points.slice(0, max).join('').trimEnd();
}

/**
 * The start of a tap-chip re-check's creator line (Ash review item 8): "Same photo, my answers:
 * ...". The chat writes it in the reply's language, like the chips. After a reload the row is
 * whatever Spring stored (Spring strips trailing spaces), so `isSamePhotoRecheckLine` is the one
 * test for it, in either language.
 */
export const PHOTO_CHECK_SAME_PHOTO_PREFIX: Record<'en' | 'hi', string> = {
  en: 'Same photo, my answers:',
  hi: 'Wahi photo, mere jawab:',
};

const SAME_PHOTO_RE = /^\s*(?:same photo, my answers|wahi photo, mere jawab)\s*:/i;

/** True when a creator row is a chip re-check of the photo before it (either language). */
export function isSamePhotoRecheckLine(text: string): boolean {
  return SAME_PHOTO_RE.test(text);
}

/**
 * Lookalike letters that fold to the Latin letters of "photo check" (after NFKD and lowercase):
 * Cyrillic, Greek, Armenian and Latin small capitals. Only used to spot a header in the MIDDLE
 * of a line; a line-leading bracket is neutralised whatever follows it.
 */
const PHOTO_CHECK_HOMOGLYPHS: Record<string, string> = {
  а: 'a', е: 'e', о: 'o', р: 'p', с: 'c', х: 'x', у: 'y', к: 'k', т: 't', һ: 'h', н: 'h', і: 'i',
  ο: 'o', ρ: 'p', τ: 't', κ: 'k', ε: 'e', χ: 'x', ϲ: 'c', η: 'h',
  օ: 'o', հ: 'h',
  ᴘ: 'p', ʜ: 'h', ᴏ: 'o', ᴛ: 't', ᴄ: 'c', ᴇ: 'e', ᴋ: 'k',
};

/** The letters of `text` only, folded: NFKD (fullwidth and styled letters become plain, accents
 *  come off), lowercase, lookalikes mapped, then every non-letter dropped. So "Photo-check",
 *  "Photo_check", "Ｐｈｏｔｏ check", "[Рhoto check" (Cyrillic Р) and a soft hyphen or bidi mark
 *  between the words all read "photocheck". */
function foldLetters(text: string): string {
  let out = '';
  for (const ch of text.normalize('NFKD').toLowerCase()) {
    const mapped = PHOTO_CHECK_HOMOGLYPHS[ch] ?? ch;
    if (/\p{L}/u.test(mapped)) out += mapped;
  }
  return out;
}

/** An opening bracket of any script (Unicode Ps): "[", "［", "⟦", "【", "〔", "{", ... */
const OPEN_BRACKET_RE = /\p{Ps}/u;
const OPEN_BRACKET_GLOBAL_RE = /\p{Ps}/gu;
/** What can come before a line's first real character without being text: whitespace, invisible
 *  format characters (bidi marks, zero-width, soft hyphen, BOM), combining marks (CGJ, variation
 *  selectors), invisible fillers, markdown lead-ins ("**", "_", ">", "-", "#", "`", "~", "+",
 *  "|", bullets) and a list number ("1." / "1)"). */
const LINE_LEAD_RE =
  /^(?:[\s\p{Cf}\p{M}\u115f\u1160\u3164\uffa0\u2800*_>#`~+\-|=\u2022\u00b7\u2023\u25e6\u25aa\u25cf]|\d{1,3}[.)])*/u;
/** A markdown checkbox ("- [ ] item", "[x] done") is a list, never a header: left alone. */
const CHECKBOX_RE = /^\[[ xX]\](?=\s|$)/;
/** Every line break a reader or a model would treat as one. */
const LINE_BREAK_SPLIT_RE = /(\r\n|[\n\r\v\f\u0085\u2028\u2029])/;
/** How far after a bracket a mid-line header is looked for (code units). */
const HEADER_LOOKAHEAD = 64;

function neutraliseLine(line: string): string {
  const chars = line.split('');
  // 1) A line whose first real character is an opening bracket: the bracket becomes "(" whatever
  //    follows it. No homoglyph list can be complete, so a line-leading "[" never reaches the model
  //    from a row that is not a real check.
  const lead = LINE_LEAD_RE.exec(line)?.[0].length ?? 0;
  const first = line.slice(lead);
  if (first && OPEN_BRACKET_RE.test(first[0]) && first[0] !== '(' && !CHECKBOX_RE.test(first)) {
    chars[lead] = '(';
  }
  // 2) Anywhere else on the line: a bracket followed by letters that fold to "photo check".
  for (const match of line.matchAll(OPEN_BRACKET_GLOBAL_RE)) {
    const at = match.index ?? 0;
    if (chars[at] === '(') continue;
    if (foldLetters(line.slice(at + 1, at + 1 + HEADER_LOOKAHEAD)).startsWith('photocheck')) chars[at] = '(';
  }
  return chars.join('');
}

/**
 * Ash review item 5, with the checker's and Kabir's bypasses closed: only a REAL photo-check row
 * (a Meera row whose card came from Spring) may start with "[Photo check". In every other row —
 * a creator who types it, or a model reply that imitates, bolds, bullets or quotes the header —
 * the header's bracket becomes "(" so the persona's "an earlier Meera turn that starts with
 * [Photo check" rule can never be met by forged text:
 *   - on every line, the first real character (after whitespace, invisible format characters,
 *     combining marks and markdown lead-ins such as "**", "- ", "> ", "# ", "1. ") becomes "(" when
 *     it is an opening bracket of any script ("[", "［", "⟦", "【", ...), whatever follows it;
 *   - anywhere on a line, an opening bracket followed by letters that fold to "photo check"
 *     (NFKD, lowercase, lookalike letters, any separator) becomes "(".
 * A markdown checkbox ("[ ]", "[x]") is left alone. The rest of the text is unchanged.
 */
export function neutralisePhotoCheckHeader(text: string): string {
  return text
    .split(LINE_BREAK_SPLIT_RE)
    .map((part, i) => (i % 2 === 1 ? part : neutraliseLine(part)))
    .join('');
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
  /** Always `undefined` BY DESIGN — no 30-day reach total is stored anywhere in the platform.
   *  The real, backend-computed reach figure the creator can see is `AccountLast28Days
   *  .accounts_reached` (last 28 FULL days, not 30, and only when `.available`). Kept on the
   *  type because the Java field still exists and the guard below does not strip it; the card
   *  no longer renders this field at all — see `MetricsCard` in
   *  `CreatorToolResultRenderer.tsx`. */
  reach_30d?: string;
  engagement_rate?: string;
  avg_reach_per_post?: string;
  verified_at?: string;
  data_source?: string;
  tier?: string;
  quality_score?: string;
}

/**
 * `CreatorToolDtos.AccountLast28Days` (2026-09-24) — the account's real Meta insights totals over
 * the last 28 full days, alongside `metrics.reach_30d` which is `undefined` BY DESIGN (no 30-day
 * total is stored anywhere — see `MetricsResult`'s own comment). `available=false` means nothing
 * has been fetched yet, with every figure `null`/absent; a single figure Meta did not return in an
 * otherwise-available fetch is `null`, never `"0"`.
 *
 * Unlike every other record in the Java file this is generated from, `AccountLast28Days` carries
 * no `@JsonInclude(NON_NULL)` of its own (`CreatorToolDtos.java` around the record's declaration),
 * so an unavailable figure can arrive as an explicit JSON `null` rather than being omitted — every
 * optional field below is typed `string | null`, not just `?: string`, so a `=== undefined` check
 * on the frontend is not enough on its own (`value ?? null` / a plain truthy check both still work,
 * since `null` is falsy exactly like `undefined`).
 */
export interface AccountLast28Days {
  available: boolean;
  period?: string | null;
  accounts_reached?: string | null;
  views?: string | null;
  interactions?: string | null;
  accounts_engaged?: string | null;
  profile_link_taps?: string | null;
}

/** `CreatorToolDtos.GetMyMetricsResult` (§3.5) — `get_my_metrics` tool result. `account_last_28_days`
 *  is a 2026-09-24 addition; older cached payloads (or a server on an older build) may omit it. */
export interface GetMyMetricsPayload {
  metrics: MetricsResult;
  account_last_28_days?: AccountLast28Days;
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

/**
 * LOCAL creator tools: influora-ai runs these inside its own tool loop (like the brand side's
 * analyze_site/present_options) and never forwards them to Spring. They are deliberately NOT in
 * `CREATOR_TOOL_NAMES` above, which mirrors influora-ai's Spring-backed `CREATOR_TOOL_NAMES`
 * tuple and is held to it by meera-api.creator-tools-in-sync.test.ts. Mirrors influora-ai's
 * separate `CREATOR_LOCAL_TOOL_NAMES`.
 *
 * `get_creator_knowledge` (input `{topic}`, result `{topic, knowledge}`) is Meera looking up
 * Influora's own notes on one topic before answering. It has no card: the chat shows one
 * friendly work-trail step for it ("Checking Influora's notes on audio") and never the returned
 * text, which is Meera's reference material, not an answer for the creator to read.
 */
export const CREATOR_LOCAL_TOOL_NAMES = ['get_creator_knowledge'] as const;

export type CreatorLocalToolName = (typeof CREATOR_LOCAL_TOOL_NAMES)[number];

export function isCreatorLocalToolName(name: string): name is CreatorLocalToolName {
  return (CREATOR_LOCAL_TOOL_NAMES as readonly string[]).includes(name);
}

/** Every tool a creator turn can show a work-trail step for: Spring-backed plus local. */
export type CreatorTrailToolName = CreatorToolName | CreatorLocalToolName;

/**
 * The `topic` values `get_creator_knowledge` accepts: influora-ai's `LOOKUP_TOPICS` keys
 * (app/prompt/content_knowledge.py), in the same order. A topic outside this list still shows a
 * step, just with the generic "Checking Influora's notes" label.
 */
export const CREATOR_KNOWLEDGE_TOPICS = ['audio', 'moving_between_spots', 'delivery_examples'] as const;

export type CreatorKnowledgeTopic = (typeof CREATOR_KNOWLEDGE_TOPICS)[number];

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

/**
 * A creator quick-action button (2026-09-22): charged as a script / profile review (3 credits by
 * default) instead of a plain message. The server decides the price; this only names the button.
 */
export type CreatorTurnAction = 'SCRIPT' | 'PROFILE_REVIEW';

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
   *
   * T-CREATOR-CREDITS-V2 (SPEC.md §9.2, F3/K-27) — `voiceReply` and `idempotencyKey` are new,
   * optional, and additive:
   *   - `voiceReply` becomes `SendTurnRequest.voiceReply` (`Boolean`, defaults to `false`
   *     server-side when omitted/null) — true when the caller's voice-reply toggle is on, so a
   *     creator turn charges 2 credits (`ChargeKind.VOICE_TURN`) instead of 1. The brand
   *     controller ignores it entirely (SPEC.md §7.1), so brand callers passing nothing here is
   *     correct, not an oversight.
   *   - `idempotencyKey`: the retry-safety fix (K-27). This method used to mint a FRESH
   *     `safeRandomUUID()` on every call under the stated assumption that a failed turn is never
   *     re-POSTed. That assumption no longer holds once a turn can be refused for a business
   *     reason (402/429) and the caller retries the exact same user message (e.g. after the
   *     creator buys more credits and presses Send again) — a second key for the same logical
   *     turn would double-charge credits for one message. Callers that may retry a message MUST
   *     mint ONE key (`safeRandomUUID()`/`crypto.randomUUID()`) per user message and pass the SAME
   *     key on every retry of it; a 409 response means the original attempt already landed and the
   *     caller's existing recovery path applies (no further resend). Omitting it preserves the old
   *     one-shot behaviour exactly (a fresh key is minted here), so every pre-existing call site is
   *     unaffected.
   */
  sendTurn: async (
    conversationId: string,
    content: string,
    role: MeeraRole = 'brand',
    options: { voiceReply?: boolean; idempotencyKey?: string; action?: CreatorTurnAction } = {}
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
    // header (MeeraController) and 400s without it.
    return request<MeeraTurnResponse>(
      'POST',
      `${basePath(role)}/sessions/${conversationId}/messages`,
      {
        body: { content, voiceReply: options.voiceReply, ...(options.action ? { action: options.action } : {}) },
        idempotencyKey: options.idempotencyKey ?? safeRandomUUID(),
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
  getHistory: async (conversationId: string, role: MeeraRole = 'brand'): Promise<MeeraHistoryItem[]> => {
    if (!isApiLive()) return [];
    // A creator ASSISTANT row may carry `card` (a stored photo check, SPEC section 2d). Read it
    // with `photoCheckFromHistoryCard`, never by parsing `content`.
    return request<MeeraHistoryItem[]>('GET', `${basePath(role)}/sessions/${conversationId}/messages`, { role });
  },

  /**
   * GET /creator/meera/sessions/{conversationId}/messages?before={messageId} — up to
   * `MEERA_HISTORY_PAGE` (50) messages OLDER than `beforeId`, oldest first (SPEC section 3b), for
   * "Show earlier messages" once the in-memory transcript runs out. Creator-only: the brand
   * controller has no `before` cursor, hence the 'creator' default. Returns [] in mock mode.
   */
  getHistoryBefore: async (
    conversationId: string,
    beforeId: string,
    role: MeeraRole = 'creator'
  ): Promise<MeeraHistoryItem[]> => {
    if (!isApiLive()) return [];
    return request<MeeraHistoryItem[]>('GET', `${basePath(role)}/sessions/${conversationId}/messages`, {
      query: { before: beforeId },
      role,
    });
  },

  /**
   * GET /meera/sessions/{conversationId}/messages?after={messageId}
   * Non-streaming fallback for fetching finalized turn
   */
  getMessagesAfter: async (
    conversationId: string,
    afterMessageId: string,
    role: MeeraRole = 'brand'
  ): Promise<MeeraHistoryItem[]> => {
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
    return request<MeeraHistoryItem[]>('GET', `${basePath(role)}/sessions/${conversationId}/messages`, {
      query: { after: afterMessageId },
      role,
    });
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
   *
   * T-CREATOR-CREDITS-V2 (SPEC.md §7.2/§9.2, F3) — `turnId` is new, optional, and appended AFTER
   * `role` (not inserted before it) so every existing 3-argument call
   * (`speak(text, lang, role)`) keeps meaning exactly what it always has; only a caller that
   * explicitly wants the credits-aware behaviour passes a 4th argument. With
   * `CREATOR_CREDITS_ENABLED` on, `VoiceSpeakRequest.turnId` is how the server decides whether
   * this reply is the paid `tts:` half of a voice turn (`hasVoiceCharge` + `claimVoiceSpeak`,
   * ≤3 calls per turn) — omitting it, or passing a turnId the server can't match to an unrefunded
   * voice charge, makes the server return `{"fallback":true}` (this method already treats a
   * non-audio response as `null`) rather than ever calling Sarvam for free. With the flag off the
   * server ignores `turnId` entirely, so passing it is always safe.
   */
  speak: async (
    text: string,
    lang?: string,
    role: MeeraRole = 'brand',
    turnId?: string
  ): Promise<Blob | null> => {
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
        body: JSON.stringify({ text, ...(lang ? { lang } : {}), ...(turnId ? { turnId } : {}) }),
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
   * itself), an optional `shot_label`, and two optional text parts, each sent only when there is
   * something in it: `shot_context` (the planned shot as a JSON object string, at most 1000
   * characters — see `serializeShotContext`) and `answers` (the coach questions the creator has
   * tapped an answer for, as a JSON array string of `{id, option}`, at most 3 items and 600
   * characters — see `serializeCoachAnswers`). Both are clipped here to the server's limits, since
   * the Java proxy answers a longer value with 400 and the check would fail outright.
   *
   * Goes through `basePath(role)` like every other method here. It used to post to a flat
   * `/ai/shoot-check/frame`, which is influora-ai's own route: this app cannot reach that service
   * directly, so every tap failed in production. `CreatorMeeraController#checkFrame` is the proxy,
   * and `meera-api.shoot-check-route.test.ts` pins this URL to that Java mapping. The creator's
   * identity comes from the auth token on the server, so no workspace id is sent.
   *
   * Same one-thing-to-check discipline as `transcribe()`, expressed as a small discriminated
   * result instead of a bare nullable: `{kind: 'ok', result}` for a real check, `{kind: 'capped',
   * message}` for the creator monthly-cap fallback (see `SHOOT_CHECK_CREATOR_CAP_CODE`), and
   * `{kind: 'unavailable'}` for every OTHER "no result" case — mock mode never hits this, but live
   * covers the endpoint not existing yet (404) or any other non-2xx, an unparsable body, ANY
   * `fallback: true` body other than the cap one, or a network error. Never throws.
   * `ShootCheckPanel` renders `'unavailable'` as a plain "couldn't check your frame right now"
   * line and does not retry automatically.
   *
   * Photo check inside Meera's chat (SPEC section 2b) adds three optional extras:
   *   - `conversationId` -> form field `conversation_id`: Spring then saves the check into that
   *     conversation as a creator row plus a Meera row, and answers with `chat` (see
   *     `MeeraPhotoCheckChat`). Spring requires an `Idempotency-Key` header with it, so one is
   *     minted here when the caller passes none; a caller that may retry the SAME capture must
   *     mint one key per capture and pass it on every retry (a replayed key returns the stored
   *     result with no second vision call).
   *   - `idempotencyKey` -> the `Idempotency-Key` header.
   *   - `userLine` -> form field `user_line`, the creator row Spring stores (one line, clipped here
   *     to `PHOTO_CHECK_USER_LINE_MAX` code points; the chat builds it — "Check my set-up: <shot>",
   *     or `PHOTO_CHECK_SAME_PHOTO_PREFIX` plus the answers for a chip re-check).
   */
  checkFrame: async (
    image: Blob,
    shotLabel: string | undefined,
    role: MeeraRole = 'creator',
    extras: {
      shotContext?: MeeraShotContext;
      answers?: MeeraCoachAnswer[];
      conversationId?: string;
      idempotencyKey?: string;
      userLine?: string;
    } = {}
  ): Promise<MeeraShootCheckFrameOutcome> => {
    const shotContextJson = serializeShotContext(extras.shotContext);
    const answersJson = serializeCoachAnswers(extras.answers);
    const conversationId = extras.conversationId?.trim() || undefined;
    const idempotencyKey = extras.idempotencyKey?.trim() || (conversationId ? safeRandomUUID() : undefined);
    const userLine = extras.userLine ? clipLine(extras.userLine, PHOTO_CHECK_USER_LINE_MAX) : '';

    if (!isApiLive()) {
      await delay(600);
      const answered = answersJson !== null;
      // Demo-mode stand-in for Spring's summary (`chat.text`); ids only when a conversation was
      // named, the same as the live server.
      const mockLabel = shotLabel ? clipLine(shotLabel, 120).replace(/[[\]]/g, '') : '';
      const mockChat: MeeraPhotoCheckChat = {
        text: [
          '[Photo check]',
          ...(mockLabel ? [`Shot: "${mockLabel}"`] : []),
          "Photo check saw: I can see you're in a bedroom, window light from behind you, phone below your eyes, framed chest up.",
          'Steps:',
          '1) Window behind you: Turn so the window is at your side, not behind you, or close the curtain and put your own light on your face.',
          '2) Eye-level phone: Phone at eye-level: neutral point of view, reliable eye contact.',
          '3) Talking head by a window: Lens: 1x Main. Distance: 0.8-1m. Framing: Chest up. EV: +0.5. Stabilization: Tripod (stabilization off).',
          'Looking good: Chest-up framing suits a talking head; The background behind you is tidy',
          "Can't tell from one photo: Whether the room is quiet enough to record; Whether you have a lamp you can move",
          ...(answered ? [] : ['I asked: Can you move to a different spot for this shot? (Yes, I can move / No, fixed spot)']),
        ].join('\n'),
        messageId: conversationId ? `mock_pc_${Date.now()}_assistant` : null,
        userMessageId: conversationId ? `mock_pc_${Date.now()}_user` : null,
      };
      // A realistic sample in the current shape: a bedroom talking head with the window behind the
      // creator, checked for an OPPO A78 (no Pro video mode, so no Pro-only settings parts).
      const demoSteps: MeeraShootCheckStep[] = [
        {
          kind: 'move_you',
          text: 'Turn so the window is at your side, not behind you, or close the curtain and put your own light on your face.',
          note: 'Window behind creator toward phone',
          label: 'Window behind you',
        },
        {
          kind: 'move_phone',
          text: 'Phone at eye-level: neutral point of view, reliable eye contact.',
          note: 'Eye-level',
          label: 'Eye-level phone',
        },
        {
          kind: 'settings',
          text: 'Lens: 1x Main. Distance: 0.8-1m. Framing: Chest up. EV: +0.5. Stabilization: Tripod (stabilization off).',
          note: 'Talking Head (Window light)',
          label: 'Talking head by a window',
          parts: [
            { label: 'Lens', value: '1x Main', needsPro: false },
            { label: 'Distance', value: '0.8-1m', needsPro: false },
            { label: 'Framing', value: 'Chest up', needsPro: false },
            { label: 'EV', value: '+0.5', needsPro: false },
            { label: 'Stabilization', value: 'Tripod (stabilization off)', needsPro: false },
          ],
        },
      ];
      return {
        kind: 'ok',
        result: {
          fixes: demoSteps.filter((s) => s.kind !== 'settings').map((s) => s.text),
          settings: demoSteps.filter((s) => s.kind === 'settings').map((s) => s.text),
          ok: ['Chest-up framing suits a talking head', 'The background behind you is tidy'],
          whatISee:
            "I can see you're in a bedroom, window light from behind you, a bright window behind you, phone below your eyes, framed chest up.",
          steps: demoSteps,
          cantTell: ['Whether the room is quiet enough to record', 'Whether you have a lamp you can move'],
          ask: answered
            ? null
            : {
                id: 'can_move',
                questionEn: 'Can you move to a different spot for this shot?',
                questionHi: 'Kya aap is shot ke liye jagah badal sakte ho?',
                options: [
                  { en: 'Yes, I can move', hi: 'Haan, jagah badal sakte hain' },
                  { en: 'No, fixed spot', hi: 'Nahi, jagah fixed hai' },
                ],
              },
          lang: 'en',
          retake: false,
        },
        chat: mockChat,
      };
    }

    try {
      const headers: Record<string, string> = {};
      const token = getToken(role);
      if (token) headers.Authorization = `Bearer ${token}`;
      if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

      const formData = new FormData();
      formData.append('image', image, 'frame.jpg');
      if (shotLabel) formData.append('shot_label', shotLabel);
      if (shotContextJson) formData.append('shot_context', shotContextJson);
      if (answersJson) formData.append('answers', answersJson);
      if (conversationId) formData.append('conversation_id', conversationId);
      if (userLine) formData.append('user_line', userLine);

      const res = await fetch(`${API_BASE_URL}${basePath(role)}/shoot-check/frame`, {
        method: 'POST',
        headers,
        credentials: 'include',
        body: formData,
      });

      if (!res.ok) return { kind: 'unavailable' };

      let body: Record<string, unknown>;
      try {
        const parsed: unknown = await res.json();
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { kind: 'unavailable' };
        body = parsed as Record<string, unknown>;
      } catch {
        return { kind: 'unavailable' };
      }

      // influora-ai's `fallback: true` envelope carries placeholder `fixes` text, never a real
      // check — see this method's doc comment. The ONE fallback body with something the creator
      // needs to see is the monthly-cap block, which the panel shows as its own message.
      if (body.fallback === true) {
        if (body.code === SHOOT_CHECK_CREATOR_CAP_CODE) {
          return {
            kind: 'capped',
            message:
              typeof body.message === 'string' && body.message.length > 0
                ? body.message
                : "You've reached your monthly Meera usage limit.",
          };
        }
        return { kind: 'unavailable' };
      }

      // `chat` is Spring's addition (never influora-ai's), so it is read here and never by the
      // body parser; an older server simply sends none.
      const chat = parsePhotoCheckChat(body.chat);
      return chat
        ? { kind: 'ok', result: parseShootCheckFrameBody(body), chat }
        : { kind: 'ok', result: parseShootCheckFrameBody(body) };
    } catch {
      return { kind: 'unavailable' };
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
