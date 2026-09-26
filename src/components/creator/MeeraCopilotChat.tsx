import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { Send, Mic, MicOff, Volume2, VolumeX, X, Loader2, AudioLines, Lock, Camera, ArrowDown } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import {
  CREATOR_HISTORY_MAX_CHARS,
  CREATOR_HISTORY_MAX_TURNS,
  recentHistory,
  type HistoryTurn,
} from '@/lib/meera-history';
import { ApiError, isApiLive } from '@/lib/api';
import {
  MEERA_HISTORY_PAGE,
  PHOTO_CHECK_SAME_PHOTO_PREFIX,
  PHOTO_CHECK_USER_LINE_MAX,
  isCreatorLocalToolName,
  isCreatorToolName,
  isSamePhotoRecheckLine,
  meeraApi,
  neutralisePhotoCheckHeader,
  photoCheckFromHistoryCard,
  type MeeraHistoryItem,
  type MeeraShootCheckAsk,
  type MeeraShotContext,
} from '@/lib/meera-api';
import {
  MeeraMessageRow,
  type ChatMessage,
  type ChatMessageResultCard,
  type CreatorToolResult,
  type PhotoCheckCard,
} from '@/components/creator/meera/MeeraMessageRow';
import { MeeraDesk } from '@/components/creator/meera/MeeraDesk';
import { MeeraCameraSheet } from '@/components/creator/meera/MeeraCameraSheet';
import { MAX_COACH_ANSWERS, replyLangFor, type CoachAnswer } from '@/components/creator/shoot-check/CoachResult';
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import type { ShootCheckLang } from '@/lib/shoot-check/advice-copy';
import { shotFromBeat, shotFromLabel } from '@/lib/shoot-check/beat-to-shot';
import { uniqueId } from '@/lib/unique-id';
import { parseMeeraReview, parseMeeraScript, type ParsedMeeraScript } from '@/lib/meera-result-cards';
import { useMeeraStream } from '@/hooks/useMeeraStream';
import { useVoiceOutput } from '@/hooks/useVoiceOutput';
import { useVoiceInput } from '@/hooks/useVoiceInput';
import { useStickToBottom } from '@/hooks/useStickToBottom';
import { useVisualViewportHeight } from '@/hooks/useVisualViewportHeight';
import { VoicePoweredOrb } from '@/components/ui/voice-powered-orb';
import { MeeraVoiceMode, type MeeraVoiceStatus } from '@/components/creator/meera/MeeraVoiceMode';
import { useToast } from '@/hooks/use-toast';
import { useCreatorCredits } from '@/hooks/useCreatorCredits';
import { CreditBalancePill } from '@/components/creator/credits/CreditBalancePill';
import { BuyCreditsSheet } from '@/components/creator/credits/BuyCreditsSheet';
import { WelcomeCreditsModal } from '@/components/creator/credits/WelcomeCreditsModal';
import { CreditCostHint } from '@/components/creator/credits/CreditCostHint';
import { ZeroCreditsBanner } from '@/components/creator/credits/ZeroCreditsBanner';
import { creditsCopy } from '@/lib/copy/creator-credits';
import {
  COMPOSER_PLACEHOLDER,
  HEADER_STATUS_LISTENING,
  HEADER_STATUS_ONLINE,
  HEADER_STATUS_SPEAKING,
  HEADER_STATUS_WORKING,
  TRAIL_UNDERSTANDING,
  TRUST_LINE,
  pickLang,
  type BilingualText,
} from '@/lib/copy/meera-chat';
import { MeeraActionStrip, MeeraQuickActions } from '@/components/creator/meera/MeeraQuickActions';
import { actionBlockedReason } from '@/lib/creator-quick-actions';
import type { CreatorTurnAction } from '@/lib/meera-api';

/**
 * T-MEERA-CREATOR-PHASE-A (A4/A5/A10, SPEC.md §4.7) — the CREATOR-side Meera chat.
 *
 * Deliberately NOT a reuse of `MeeraChatPanel.tsx`: that component is wired end-to-end to the
 * brand Living Canvas (stage advancement, tool_result cards, credits paywall, present_options)
 * per its own file-level docs, none of which exist for a creator turn in Phase A ("conversational
 * + profile/deals summary only" — SPEC.md §7 explicitly excludes creator tools). Forking a
 * matching-but-different panel avoids either (a) threading an `audience` prop through brand-only
 * logic that would need to no-op for every branch, or (b) risking a brand regression editing a
 * component with existing brand test coverage. Both panels talk to the SAME `meeraApi` — this one
 * just passes `role: 'creator'` everywhere (see meera-api.ts's MeeraRole threading).
 *
 * Photo check in Meera's chat (2026-09-26 build SPEC): the old Shoot Check page's "Check my
 * frame" now lives here. A camera sheet (composer camera button, or "Check my set-up" on a script
 * beat) captures one still; the result comes back as Meera's message with the coach card, and
 * Spring stores a text summary of it so later turns ("now I'm standing") can use it.
 *
 * Long chats (same SPEC, section 3): rows are memoised (`MeeraMessageRow`), the list follows new
 * content only when the reader is at the bottom (`useStickToBottom`), only the newest 50 rows are
 * in the DOM ("Show earlier messages" reveals more, then pages older ones from the server), and
 * rows that exist only on this screen are never replayed to the model (`buildModelHistory`).
 */

/** What mock mode answers with when there is no backend to talk to. */
const MOCK_MODE_REPLY = 'This is mock mode — connect a live backend to chat with Meera.';

/** DOM window (SPEC 3b): rows rendered at once; "Show earlier messages" reveals this many more. */
const CHAT_RENDER_WINDOW = 50;
/** The creator `messages()` endpoint's first page (MeeraSessionService's cap). A first load this
 *  long may have older rows on the server. */
const CREATOR_HISTORY_FIRST_PAGE = 100;
const PHONE_QUERY = '(max-width: 639px)';

/** Hindi chat chrome is Devanagari (like the placeholder, the trust line and the lines below).
 *  Only the check's own result card and its re-check line keep the server's Hinglish register. */
const CAMERA_BUTTON: BilingualText = { en: 'Check my set-up', hi: 'सेट-अप चेक करें' };
const SHOW_EARLIER: BilingualText = { en: 'Show earlier messages', hi: 'पहले के मैसेज दिखाएं' };
const NEW_MESSAGES: BilingualText = { en: 'New messages', hi: 'नए मैसेज' };
const PHOTO_CHECK_UNAVAILABLE: BilingualText = {
  en: "I couldn't check your photo right now. Try again in a minute.",
  hi: 'अभी आपकी फ़ोटो चेक नहीं हो पाई। एक मिनट बाद फिर कोशिश करें।',
};
/** The streamed and the non-streaming path's stand-in for a blank reply. Meera never said it, so
 *  the row is `localOnly` and never replayed to her. */
const BLANK_REPLY_TEXT = 'Sorry, I lost my train of thought there. Say that again?';

function parseResultCard(text: string): ChatMessageResultCard | undefined {
  const script = parseMeeraScript(text);
  if (script) return { kind: 'script', script };
  const review = parseMeeraReview(text);
  if (review) return { kind: 'review', review };
  return undefined;
}

/** The `topic` string out of a `get_creator_knowledge` tool_start input or result payload. */
function knowledgeTopicOf(value: unknown): string | undefined {
  if (value && typeof value === 'object') {
    const topic = (value as { topic?: unknown }).topic;
    if (typeof topic === 'string' && topic) return topic;
  }
  return undefined;
}

/**
 * The human-readable reason out of an ERROR `tool_result` payload. The Python loop yields
 * `{error: <code>, message: <text>}` on a Spring/mesh failure, and without this the card can only
 * show its own generic sentence — masking the actual cause (auth/mesh/scope).
 */
function toolErrorMessage(data: unknown): string | undefined {
  if (data && typeof data === 'object') {
    const d = data as { message?: unknown; error?: unknown };
    if (typeof d.message === 'string' && d.message) return d.message;
    if (typeof d.error === 'string' && d.error) return d.error;
  }
  return undefined;
}

const CONSENT_ERROR_CODE = 'CONSENT_REQUIRED';
/** influora-ai spend_tracker.py (A8, TASKS.md dev notes 2026-09-03) — 429 over the per-creator
 *  monthly cap, with a friendly message the caller should show verbatim rather than the generic
 *  "something went wrong" fallback. */
const CAP_REACHED_ERROR_CODE = 'CREATOR_MONTHLY_CAP_REACHED';

/**
 * T-CREATOR-CREDITS-V2 (SPEC.md §8 "Refusal codes") — NOT the same family as
 * `CAP_REACHED_ERROR_CODE` above (that one is the pre-existing USD spend-tracker cap from
 * `influora-ai`'s `spend_tracker.py`). These two are the credits ledger's own refusals
 * (`CreatorCreditService.refusal`): 402 when the balance is insufficient, 429 at the daily cap.
 * Both carry a server-templated `message` (en/hi by `creator_language`) that this component shows
 * verbatim, exactly like `CAP_REACHED_ERROR_CODE` already does — R6 is "never silent", not
 * "silent unless it's the other cap".
 */
const CREDITS_EXHAUSTED_ERROR_CODE = 'CREATOR_CREDITS_EXHAUSTED';
const CREDITS_DAILY_CAP_ERROR_CODE = 'CREATOR_DAILY_CAP_REACHED';

function isCreditsRefusal(code: string): boolean {
  return code === CREDITS_EXHAUSTED_ERROR_CODE || code === CREDITS_DAILY_CAP_ERROR_CODE;
}

/**
 * Matched on `code` only (SPEC.md §3.4's `HTTPException(403, {"code": "CONSENT_REQUIRED", ...})`),
 * deliberately NOT on a bare 403 — a 403 can also mean "wrong role"/"session expired", and
 * mis-routing either of those into the consent screen would hide the real problem.
 */
export function isConsentRequiredError(err: unknown): boolean {
  return err instanceof ApiError && err.code === CONSENT_ERROR_CODE;
}

/**
 * Fix round 1, Q4 item (3): useMeeraStream's `fail()` sets `event.message` to transport-internal
 * strings like "Stream connection failed" / "Stream ended before completion" — accurate for a
 * console.error, not something to hand a creator verbatim. Map the known transport codes to an
 * honest, human sentence; anything else (including CREATOR_MONTHLY_CAP_REACHED, which already
 * carries its own friendly copy from spend_tracker.py) falls through to `event.message` as-is.
 */
function friendlyStreamErrorText(code: string, message: string | undefined): string {
  switch (code) {
    case 'CONNECTION_ERROR':
      return "Lost the connection to Meera — try again?";
    case 'STREAM_INCOMPLETE':
      return "Meera's reply got cut off — try again?";
    default:
      return message ?? "Didn't catch that — try again?";
  }
}

/** Client-side-only greeting shown while a brand-new conversation has no history yet. Never
 *  sent to the model as history (`localOnly`) — purely a "someone's home" placeholder while the
 *  backend's own day-one onboarding turn (SPEC.md §4.7) lands on the wire. */
function onboardingGreeting(firstName: string, language: string): string {
  return language.startsWith('hi')
    ? `नमस्ते ${firstName}! मैं Meera हूं, Influora पर आपकी मैनेजर। मैं आपकी डील्स, कमाई और मेट्रिक्स समझने में मदद कर सकती हूं। आप क्या जानना चाहेंगे?`
    : `Hi ${firstName}! I'm Meera, your manager here on Influora. I can help you track your deals, understand your earnings, and answer questions about the platform. What would you like to know?`;
}

function newIdempotencyKey(prefix: string): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : uniqueId(prefix);
}

/** Clip to `max` Unicode code points (the server counts code points, not UTF-16 units). */
function clipCodePoints(text: string, max: number): string {
  const points = Array.from(text);
  return points.length <= max ? text : points.slice(0, max).join('');
}

/** Same feature test as useShootCheck's own: the camera button is hidden without it. */
function cameraSupportedNow(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    Boolean(navigator.mediaDevices?.getUserMedia) &&
    window.isSecureContext
  );
}

function shootLangFor(language: string): ShootCheckLang {
  return language.startsWith('hi') ? 'hi-IN' : 'en-IN';
}

/** The creator line that starts a check: "Check my set-up: <shot label>", or without a shot. */
function checkUserLine(shot: ShootCheckShot | null, language: string): string {
  const base = pickLang(language, CAMERA_BUTTON);
  const label = shot?.label.trim();
  return clipCodePoints(label ? `${base}: ${label}` : base, PHOTO_CHECK_USER_LINE_MAX);
}

/** The planned shot as `shot_context`; the label stands in as `line` when the beat gave none
 *  (the same rule the old Shoot Check page used). */
function shotContextFor(shot: ShootCheckShot | null): MeeraShotContext | undefined {
  if (!shot) return undefined;
  const context: MeeraShotContext = { ...(shot.context ?? {}) };
  if (shot.label.trim() && !context.line?.trim()) context.line = shot.label.trim();
  return Object.values(context).some((v) => typeof v === 'string' && v.trim()) ? context : undefined;
}

/**
 * The history a chat turn sends to the model (long-chat bug 4, Ash 5 and 8):
 * - rows marked `localOnly` (greeting, mock reply, errors, timeouts, credits refusals, photo-check
 *   placeholders and failures) and rows with no text are dropped: Meera never said them;
 * - only the NEWEST check of each photo is kept, with its own creator line (a chip re-check
 *   replaces the check before it, instead of taking two more of the 20 history slots);
 * - every row that is not a real photo-check card has a leading "[Photo check" neutralised;
 * - then the usual 20-message / 16,000-character window (meera-history).
 */
function buildModelHistory(messages: ChatMessage[], newUserText: string): HistoryTurn[] {
  const newestOfPhoto = new Map<string, string>();
  for (const m of messages) {
    if (m.resultCard?.kind === 'photoCheck' && !m.localOnly && m.text.trim()) {
      newestOfPhoto.set(m.resultCard.photoId, m.id);
    }
  }
  const skip = new Set<string>();
  messages.forEach((m, i) => {
    if (m.resultCard?.kind !== 'photoCheck') return;
    const photoId = m.resultCard.photoId;
    if (newestOfPhoto.get(photoId) === m.id) return;
    skip.add(m.id);
    const before = messages[i - 1];
    if (before && before.role === 'creator' && before.photoCheckUserOf === photoId) skip.add(before.id);
  });

  const turns: HistoryTurn[] = [];
  for (const m of messages) {
    if (m.localOnly || m.photoCheckPending || skip.has(m.id) || !m.text.trim()) continue;
    const realCheck = m.role === 'meera' && m.resultCard?.kind === 'photoCheck';
    turns.push({
      role: m.role === 'creator' ? 'user' : 'assistant',
      // Ash 5: only a real card row may start a line with "[Photo check" (meera-api.ts).
      content: realCheck ? m.text : neutralisePhotoCheckHeader(m.text),
    });
  }
  turns.push({ role: 'user', content: neutralisePhotoCheckHeader(newUserText) });
  return recentHistory(turns, CREATOR_HISTORY_MAX_TURNS, CREATOR_HISTORY_MAX_CHARS);
}

/**
 * Stored history rows to chat rows. A row with a server `card` of kind `photo_check` becomes a
 * photo-check card built from that metadata (SPEC 2d) — NEVER from its text, so a reply that
 * imitates the summary cannot become a card. A re-check (its creator row starts with the
 * "Same photo" line, `isSamePhotoRecheckLine`) keeps the photo id of the check before it, so the
 * replay rule above still keeps only the newest check of each photo after a reload.
 */
function mapHistoryItems(items: MeeraHistoryItem[]): ChatMessage[] {
  const rows: ChatMessage[] = [];
  let lastPhotoId: string | null = null;
  for (const item of items) {
    const role: ChatMessage['role'] = item.role === 'ASSISTANT' ? 'meera' : 'creator';
    const stored = role === 'meera' ? photoCheckFromHistoryCard(item.card) : null;
    if (stored) {
      const before = rows[rows.length - 1];
      const recheck =
        !!before &&
        before.role === 'creator' &&
        lastPhotoId !== null &&
        isSamePhotoRecheckLine(before.text);
      const photoId: string = recheck && lastPhotoId ? lastPhotoId : uniqueId('photo');
      lastPhotoId = photoId;
      if (before && before.role === 'creator') rows[rows.length - 1] = { ...before, photoCheckUserOf: photoId };
      const card: PhotoCheckCard = {
        kind: 'photoCheck',
        result: stored.result,
        shotLabel: stored.shotLabel,
        photoId,
      };
      rows.push({ id: item.id, role, text: item.content, resultCard: card });
      continue;
    }
    // History text is always complete (never a streaming partial), so it is safe to parse
    // immediately rather than deferring to an `onDone` this row has no stream for.
    rows.push({ id: item.id, role, text: item.content, resultCard: role === 'meera' ? parseResultCard(item.content) : undefined });
  }
  return rows;
}

/** Every beat of a script as a camera-sheet shot. */
function shotsOfScript(script: ParsedMeeraScript): ShootCheckShot[] {
  return script.beats.map((_, i) => shotFromBeat(script, i));
}

/** The newest script card in the chat, for the composer camera's shot picker. */
function newestScript(messages: ChatMessage[]): ParsedMeeraScript | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const card = messages[i].resultCard;
    if (card?.kind === 'script' && card.script.beats.length > 0) return card.script;
  }
  return null;
}

/** The one in-memory still for chip re-checks (SPEC 1e): the newest check only, never persisted,
 *  gone when the chat unmounts. */
interface PhotoCheckSession {
  messageId: string;
  photoId: string;
  blob: Blob;
  shot: ShootCheckShot | null;
  answers: CoachAnswer[];
}

export interface MeeraCopilotChatProps {
  firstName: string;
  /** BCP-47 — from CreatorAgentPreferences.creator_language (A5). */
  language: string;
  onClose: () => void;
  /** Bubbles a CONSENT_REQUIRED failure up so the caller can re-show the consent screen
   *  (e.g. consent was revoked/expired mid-session). */
  onConsentRequired: () => void;
  /**
   * U-5 (RULINGS-U-0917.md R-U1) — a message to FILL the composer with, never to send. Priya's
   * ruling changed SPEC §8.5's "Open in Meera" from auto-send to prefill-only: an automatic send
   * needs a send-once guard that survives an async connect, StrictMode's double effect and a
   * consent screen in between, and prefill has none of those failure modes.
   *
   * `token` must change on every request, even when `text` repeats (e.g. the creator clicks "Ask
   * Meera about this brief" twice), so a genuinely new request is never ignored just because its
   * text matches the last one. It is compared against the last APPLIED token, not the last SEEN
   * one, so an unrelated re-render of the caller never re-fires it. If the creator already typed
   * something, that text stays and the message is appended — never overwritten. A second request
   * that resolves before the first one's own effect ran (a fast double-click) still carries a
   * DIFFERENT token, so token equality alone would append twice; the composer text itself is also
   * checked (see the effect below) so the prompt is never appended back-to-back with itself.
   */
  prefillMessage?: { text: string; token: number } | null;
  /**
   * 2026-09-22 — the "Analyse a brief" quick-action button hands off to the page's own brief card
   * (the brief flow already exists there, with its own 3-credit charge). Omitted = no brief button.
   */
  onAnalyseBrief?: () => void;
  /**
   * Photo check SPEC section 4 — the old `/creator/shoot-check` link lands here with `?camera=1`.
   * The camera sheet opens once, after the chat has connected (live: once it has a conversation).
   */
  openCameraOnMount?: boolean;
}

export function MeeraCopilotChat({
  firstName,
  language,
  onClose,
  onConsentRequired,
  prefillMessage,
  onAnalyseBrief,
  openCameraOnMount = false,
}: MeeraCopilotChatProps) {
  const [live] = React.useState(() => isApiLive());
  const [messages, setMessages] = React.useState<ChatMessage[]>([]);
  const [conversationId, setConversationId] = React.useState<string | null>(null);
  const [connecting, setConnecting] = React.useState(true);
  const [connectError, setConnectError] = React.useState<string | null>(null);
  const [sending, setSending] = React.useState(false);
  /**
   * MEERA-CHAT-DESIGN-SPEC.md Part A — true once the CURRENT turn has produced its first real
   * signal (a token or a tool_start). Before that, and only while `sending`, the messages list
   * shows the one placeholder trail step "Understanding your question…". Reset at every send.
   */
  const [turnStarted, setTurnStarted] = React.useState(false);
  const [draft, setDraft] = React.useState('');
  // 2026-09-22 — a quick-action button the chat box is currently in ("Write a script" /
  // "Review my profile"). The next Send goes out as that action and is charged as it.
  const [action, setAction] = React.useState<CreatorTurnAction | null>(null);
  const rootRef = React.useRef<HTMLDivElement>(null);
  const scrollRef = React.useRef<HTMLDivElement>(null);
  // Round 2 QA — a stable id for the composer `<Textarea>` so `handleSendClick` can focus it
  // without needing `Textarea` (a shared shadcn primitive) to forward a ref.
  const composerId = React.useId();
  const reduceMotion = useReducedMotion();
  const stream = useMeeraStream();
  const { toast } = useToast();

  // Photo check (SPEC 1b-1e).
  const [cameraSupported] = React.useState(cameraSupportedNow);
  const [camera, setCamera] = React.useState<{ shots: ShootCheckShot[]; initialShotIndex?: number } | null>(null);
  const [checking, setChecking] = React.useState(false);
  const [liveCheckId, setLiveCheckId] = React.useState<string | null>(null);
  // The newest check's photo, for its Reel layout guide (spec v2 Phase 4). Same lifetime as the
  // session's blob: replaced by the next check, gone when the chat unmounts, never stored.
  const [livePhoto, setLivePhoto] = React.useState<Blob | null>(null);
  const photoSessionRef = React.useRef<PhotoCheckSession | null>(null);

  // Long chats (SPEC 3b): the DOM window starts at this row; null means "the newest 50".
  const [firstVisibleId, setFirstVisibleId] = React.useState<string | null>(null);
  const [olderOnServer, setOlderOnServer] = React.useState(false);
  const [loadingOlder, setLoadingOlder] = React.useState(false);
  const anchorRef = React.useRef<{ height: number; top: number } | null>(null);

  // T-CREATOR-CREDITS-V2 (SPEC.md §9.3) — the ONE `GET /creator/credits` fetch for this panel.
  const credits = useCreatorCredits();
  const refreshCredits = credits.refresh;
  const [buySheetOpen, setBuySheetOpen] = React.useState(false);

  const {
    supported: voiceOutputSupported,
    enabled: voiceEnabled,
    setEnabled: setVoiceEnabled,
    isSpeaking,
    speak,
    stop: stopSpeaking,
  } = useVoiceOutput('creator');

  const handleVoiceResult = React.useCallback((text: string) => {
    setDraft((prev) => (prev ? `${prev} ${text}` : text));
  }, []);

  const { supported: voiceInputSupported, isListening, start: startListening, stop: stopListening } =
    useVoiceInput({ onResult: handleVoiceResult, lang: language, role: 'creator' });

  // Voice mode: a full-screen stage over the same chat. It reuses the composer (the mic writes into
  // `draft`) and the same handleSend, so nothing is sent without the creator pressing Send. Voice
  // replies are switched on while it is open and put back to the creator's own choice on close.
  const [voiceModeOpen, setVoiceModeOpen] = React.useState(false);
  const voiceEnabledBeforeModeRef = React.useRef<boolean | null>(null);
  const openVoiceMode = () => {
    voiceEnabledBeforeModeRef.current = voiceEnabled;
    if (!voiceEnabled) setVoiceEnabled(true);
    setVoiceModeOpen(true);
  };
  const handleVoiceModeChange = (open: boolean) => {
    if (open) return;
    if (isListening) stopListening();
    if (voiceEnabledBeforeModeRef.current === false) setVoiceEnabled(false);
    voiceEnabledBeforeModeRef.current = null;
    setVoiceModeOpen(false);
  };

  // What the stable row callbacks below read at call time. Written after every commit, never
  // during render, so the callbacks themselves never change identity (memoised rows, SPEC 3b).
  // The voice hooks' stop functions are read here too, so no row callback depends on their
  // identity.
  const latestRef = React.useRef({
    messages,
    sending,
    checking,
    voiceModeOpen,
    isListening,
    conversationId,
    language,
    stopListening,
    stopSpeaking,
  });
  React.useLayoutEffect(() => {
    latestRef.current = {
      messages,
      sending,
      checking,
      voiceModeOpen,
      isListening,
      conversationId,
      language,
      stopListening,
      stopSpeaking,
    };
  });

  // ---- Scroll that respects the reader (SPEC 3b) -------------------------------------------
  const lastRow = messages[messages.length - 1];
  const contentKey = [
    lastRow?.id ?? '',
    lastRow?.text.length ?? 0,
    lastRow?.toolResults?.map((t) => t.status).join(',') ?? '',
    lastRow?.photoCheckPending ? 'p' : '',
    sending ? 's' : '',
    turnStarted ? 't' : '',
  ].join('|');
  const stick = useStickToBottom(scrollRef, { contentKey, reduceMotion });
  const { forceScroll } = stick;

  /**
   * Fix round 1, Q4 item (2): pulled out of the mount effect so the `connectError` retry button
   * can re-run the exact same connect sequence instead of forcing a full page reload — a dead
   * Spring at panel-open time previously had no recovery path but a reload.
   */
  const connectToMeera = React.useCallback(
    (onCancelledRef: { current: boolean }) => {
      setConnecting(true);
      setConnectError(null);
      meeraApi
        .startSession('creator')
        .then((session) => {
          if (onCancelledRef.current) return;
          setConversationId(session.conversationId);
          return meeraApi
            .getHistory(session.conversationId, 'creator')
            .then((history) => {
              if (onCancelledRef.current) return;
              forceScroll();
              if (history.length > 0) {
                setMessages(mapHistoryItems(history));
                setFirstVisibleId(null);
                setOlderOnServer(history.length >= CREATOR_HISTORY_FIRST_PAGE);
              } else {
                // Backend day-one onboarding hasn't sent a first turn yet (or this build predates
                // it) — show a local, non-persisted greeting so the panel never opens blank.
                setMessages([
                  { id: uniqueId('meera-greet'), role: 'meera', text: onboardingGreeting(firstName, language), localOnly: true },
                ]);
              }
            })
            .catch(() => {
              setMessages([
                { id: uniqueId('meera-greet'), role: 'meera', text: onboardingGreeting(firstName, language), localOnly: true },
              ]);
            });
        })
        .catch((err: unknown) => {
          if (onCancelledRef.current) return;
          if (isConsentRequiredError(err)) {
            onConsentRequired();
            return;
          }
          setConnectError("Couldn't reach Meera.");
        })
        .finally(() => {
          if (!onCancelledRef.current) setConnecting(false);
        });
    },
    [firstName, language, onConsentRequired, forceScroll],
  );

  React.useEffect(() => {
    if (!live) {
      setConnecting(false);
      setMessages([
        { id: uniqueId('meera-mock'), role: 'meera', text: onboardingGreeting(firstName, language), localOnly: true },
      ]);
      return;
    }
    const cancelledRef = { current: false };
    connectToMeera(cancelledRef);
    return () => {
      cancelledRef.current = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live]);

  const handleRetryConnect = React.useCallback(() => {
    connectToMeera({ current: false });
  }, [connectToMeera]);

  // U-5 — fill the composer, never send. The TOKEN guard below stops an unrelated re-render of
  // the caller (new `prefillMessage` object, same token) from re-firing; the effect's own
  // text-suffix check (PRIYA-LASTCALL-U3-U5-0917.md, U-5 LOW (c)) is what makes a back-to-back
  // repeat of the same prompt a no-op instead of appending it twice.
  const appliedPrefillTokenRef = React.useRef<number | null>(null);
  React.useEffect(() => {
    if (!prefillMessage || prefillMessage.token === appliedPrefillTokenRef.current) return;
    appliedPrefillTokenRef.current = prefillMessage.token;
    setDraft((prev) => {
      if (prev.endsWith(prefillMessage.text)) return prev;
      return prev.trim() ? `${prev} ${prefillMessage.text}` : prefillMessage.text;
    });
  }, [prefillMessage]);

  React.useEffect(() => {
    return () => stopSpeaking();
  }, [stopSpeaking]);

  /**
   * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F8) — the monthly-grant and 7-day paid-expiry toasts.
   * Both are "at most once" guards backed by `localStorage` (try/catch — same discipline as
   * `WelcomeCreditsModal`'s seen-marker): the monthly one keyed by `monthly.period`, the expiry
   * one keyed by the IST calendar day.
   */
  React.useEffect(() => {
    const balance = credits.balance;
    if (!balance?.enabled) return;

    const period = balance.monthly?.period;
    if (period && balance.monthly?.granted) {
      const key = 'creator-credits:monthly-toast-period';
      try {
        if (window.localStorage.getItem(key) !== period) {
          window.localStorage.setItem(key, period);
          toast({ title: creditsCopy('monthly', language) });
        }
      } catch {
        // Non-fatal — worst case the toast repeats on a later visit.
      }
    }

    const soonest = balance.paidExpiring?.[0];
    if (soonest) {
      const daysLeft = (new Date(soonest.expiresAt).getTime() - Date.now()) / 86_400_000;
      if (daysLeft <= 7 && daysLeft >= 0) {
        const todayIst = new Date().toISOString().slice(0, 10);
        const key = 'creator-credits:expiring-toast-date';
        try {
          if (window.localStorage.getItem(key) !== todayIst) {
            window.localStorage.setItem(key, todayIst);
            toast({
              title: creditsCopy('expiring', language, {
                count: soonest.credits,
                date: new Date(soonest.expiresAt).toLocaleDateString(),
              }),
            });
          }
        } catch {
          // Non-fatal.
        }
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [credits.balance?.monthly?.period, credits.balance?.paidExpiring, language]);

  /**
   * MEERA-CHAT-DESIGN-SPEC.md Part B — the desk's tiles/starter prompts and "Ask Meera my rate"
   * all go through this: fill the composer, never send (R-U1).
   */
  const prefillComposer = React.useCallback((text: string) => {
    setDraft((prev) => (prev.trim() ? `${prev} ${text}` : text));
  }, []);

  // ---- Stable row callbacks (SPEC 3b) -------------------------------------------------------
  const handleToggleRaw = React.useCallback((messageId: string) => {
    setMessages((prev) => prev.map((row) => (row.id === messageId ? { ...row, showRawText: !row.showRawText } : row)));
  }, []);

  const handleCredited = React.useCallback(() => {
    void refreshCredits();
  }, [refreshCredits]);

  /** Opens the camera sheet. Stops the mic and any reply being read aloud first, and never opens
   *  over voice mode (SPEC 1b "Before opening"). */
  const openCamera = React.useCallback(
    (shots: ShootCheckShot[], initialShotIndex?: number) => {
      const latest = latestRef.current;
      if (latest.voiceModeOpen || latest.checking || latest.sending) return;
      if (latest.isListening) latest.stopListening();
      latest.stopSpeaking();
      setCamera({ shots, initialShotIndex });
    },
    [],
  );

  const handleCheckShot = React.useCallback(
    (messageId: string, beatIndex: number) => {
      const row = latestRef.current.messages.find((m) => m.id === messageId);
      if (row?.resultCard?.kind !== 'script') return;
      openCamera(shotsOfScript(row.resultCard.script), beatIndex);
    },
    [openCamera],
  );

  const openComposerCamera = React.useCallback(() => {
    const script = newestScript(latestRef.current.messages);
    openCamera(script ? shotsOfScript(script) : []);
  }, [openCamera]);

  /**
   * One photo check, start to finish (SPEC 1c/1d): the creator's line and a "Looking at your
   * photo…" placeholder go in at once, then `checkFrame` (with the conversation, so Spring stores
   * the pair, and a fresh idempotency key), then the placeholder becomes the result card — or a
   * `localOnly` failure bubble, and the creator's line turns `localOnly` too, since nothing was
   * saved. On success the rows take the server's ids.
   */
  const runPhotoCheck = React.useCallback(
    async (args: { blob: Blob; shot: ShootCheckShot | null; answers: CoachAnswer[]; photoId: string; userLine: string }) => {
      const latest = latestRef.current;
      if (latest.checking) return;
      latest.checking = true;
      setChecking(true);
      const userRowId = uniqueId('creator-check');
      const placeholderId = uniqueId('meera-check');
      forceScroll();
      setMessages((prev) => [
        ...prev,
        { id: userRowId, role: 'creator', text: args.userLine, photoCheckUserOf: args.photoId },
        { id: placeholderId, role: 'meera', text: '', photoCheckPending: true, localOnly: true },
      ]);
      const conversation = live ? latest.conversationId : null;
      try {
        const outcome = await meeraApi.checkFrame(args.blob, args.shot?.label.trim() || undefined, 'creator', {
          shotContext: shotContextFor(args.shot),
          answers: args.answers.map((a) => ({ id: a.ask.id, option: a.option })),
          ...(conversation ? { conversationId: conversation } : {}),
          idempotencyKey: newIdempotencyKey('photo-check'),
          userLine: args.userLine,
        });

        if (outcome.kind === 'ok') {
          const chat = outcome.chat;
          const text = chat?.text?.trim() ? chat.text : '';
          const cardId = chat?.messageId || placeholderId;
          const userId = chat?.userMessageId || userRowId;
          const card: PhotoCheckCard = {
            kind: 'photoCheck',
            result: outcome.result,
            shotLabel: args.shot?.label.trim() || undefined,
            shot: args.shot,
            answers: args.answers,
            photoId: args.photoId,
          };
          setMessages((prev) =>
            prev.map((m) => {
              if (m.id === userRowId) return { ...m, id: userId, ...(text ? {} : { localOnly: true as const }) };
              if (m.id === placeholderId) {
                return { id: cardId, role: 'meera', text, resultCard: card, ...(text ? {} : { localOnly: true as const }) };
              }
              return m;
            }),
          );
          photoSessionRef.current = { messageId: cardId, photoId: args.photoId, blob: args.blob, shot: args.shot, answers: args.answers };
          setLiveCheckId(cardId);
          setLivePhoto(args.blob);
          return;
        }

        const failText =
          outcome.kind === 'capped' ? outcome.message : pickLang(latest.language, PHOTO_CHECK_UNAVAILABLE);
        setMessages((prev) =>
          prev.map((m) => {
            if (m.id === userRowId) return { ...m, localOnly: true };
            if (m.id === placeholderId) return { id: placeholderId, role: 'meera', text: failText, localOnly: true };
            return m;
          }),
        );
      } finally {
        latestRef.current.checking = false;
        setChecking(false);
      }
    },
    [forceScroll, live],
  );

  const handleCapture = React.useCallback(
    (blob: Blob, shot: ShootCheckShot | null) => {
      setCamera(null);
      void runPhotoCheck({
        blob,
        shot,
        answers: [],
        photoId: uniqueId('photo'),
        userLine: checkUserLine(shot, latestRef.current.language),
      });
    },
    [runPhotoCheck],
  );

  /** A tapped chip re-checks the SAME still with the answers so far (SPEC 1e). Only the newest
   *  card's chips are live; anything else is ignored. */
  const handleCoachAnswer = React.useCallback(
    (messageId: string, ask: MeeraShootCheckAsk, option: number) => {
      const session = photoSessionRef.current;
      const latest = latestRef.current;
      if (!session || session.messageId !== messageId || latest.checking || latest.sending) return;
      const row = latest.messages.find((m) => m.id === messageId);
      if (row?.resultCard?.kind !== 'photoCheck') return;
      const replyLang = replyLangFor(row.resultCard.result, shootLangFor(latest.language));
      const answers = [...session.answers.filter((a) => a.ask.id !== ask.id), { ask, option }].slice(-MAX_COACH_ANSWERS);
      const said = answers.map((a) => a.ask.options[a.option]?.[replyLang] ?? '').filter(Boolean);
      const prefix = PHOTO_CHECK_SAME_PHOTO_PREFIX[replyLang];
      void runPhotoCheck({
        blob: session.blob,
        shot: session.shot,
        answers,
        photoId: session.photoId,
        userLine: clipCodePoints(said.length > 0 ? `${prefix} ${said.join('; ')}` : prefix, PHOTO_CHECK_USER_LINE_MAX),
      });
    },
    [runPhotoCheck],
  );

  /** "Check again" (or "Take a new photo" on an older card): the camera on the same shot. A card
   *  that came back from history has only its label, so the shot (framing target, angle, action,
   *  on_camera) is rebuilt from it (`shotFromLabel`). */
  const handlePhotoRetake = React.useCallback(
    (messageId: string) => {
      const row = latestRef.current.messages.find((m) => m.id === messageId);
      if (row?.resultCard?.kind !== 'photoCheck') return;
      const { shot, shotLabel } = row.resultCard;
      const again: ShootCheckShot | null = shot ?? (shotLabel ? shotFromLabel(shotLabel) : null);
      openCamera(again ? [again] : [], again ? 0 : undefined);
    },
    [openCamera],
  );

  // The old /creator/shoot-check link (SPEC 4): open the camera once, after connecting.
  const openedOnMountRef = React.useRef(false);
  React.useEffect(() => {
    if (!openCameraOnMount || openedOnMountRef.current) return;
    if (connecting || connectError) return;
    if (live && !conversationId) return;
    openedOnMountRef.current = true;
    openComposerCamera();
  }, [openCameraOnMount, connecting, connectError, live, conversationId, openComposerCamera]);

  const handleSend = () => {
    const typed = draft.trim();
    const currentAction = action;
    // Ash 13: never while a photo check is in flight, or the stored order would differ from the
    // screen's (the check's pair is written when it completes).
    if (sending || checking) return;
    // A script needs a topic; a profile review may be sent with no extra text.
    if (!typed && currentAction !== 'PROFILE_REVIEW') return;
    if (currentAction && actionBlockedReason(currentAction, credits.balance, language)) return;
    const text =
      currentAction === 'SCRIPT'
        ? creditsCopy('action.scriptPrompt', language, { topic: typed })
        : currentAction === 'PROFILE_REVIEW'
          ? typed
            ? creditsCopy('action.profilePromptFocus', language, { topic: typed })
            : creditsCopy('action.profilePrompt', language)
          : typed;
    setAction(null);
    setDraft('');
    // A send jumps to the bottom, and the DOM window goes back to the newest rows (plus this turn).
    forceScroll();
    const windowStart = messages[Math.max(0, messages.length + 1 - CHAT_RENDER_WINDOW)];
    setFirstVisibleId(messages.length + 1 > CHAT_RENDER_WINDOW && windowStart ? windowStart.id : null);
    setMessages((prev) => [...prev, { id: uniqueId('creator'), role: 'creator', text }]);

    if (!live) {
      // Mock mode — no backend to talk to. Echo a short, honest placeholder.
      window.setTimeout(() => {
        setMessages((prev) => [
          ...prev,
          // Parsed like every other Meera message (Phase C): a demo must show what the live
          // product shows, so a script/review reply renders as a card here too.
          {
            id: uniqueId('meera-mock'),
            role: 'meera',
            text: MOCK_MODE_REPLY,
            resultCard: parseResultCard(MOCK_MODE_REPLY),
            localOnly: true,
          },
        ]);
      }, 500);
      return;
    }

    if (!conversationId) return;
    setSending(true);
    setTurnStarted(false);
    const assistantId = uniqueId('meera');
    let assistantText = '';
    let bubbleAdded = false;
    // Built from the rows as they were when the creator pressed Send (bug 4: local-only rows out).
    const conversation = buildModelHistory(messages, text);

    /**
     * §8.3 — edit this turn's tool-result list, creating the assistant bubble FIRST if the stream
     * has not produced a token yet (a `tool_start` can arrive before any token). `bubbleAdded` is
     * assigned inside the updater because `exists` is only knowable there; the assignment is
     * idempotent, so a double-invoked updater (StrictMode) is harmless.
     */
    const editToolResults = (edit: (current: CreatorToolResult[]) => CreatorToolResult[]) => {
      setMessages((prev) => {
        const exists = prev.some((m) => m.id === assistantId);
        const base = exists ? prev : [...prev, { id: assistantId, role: 'meera' as const, text: assistantText }];
        if (!exists) bubbleAdded = true;
        return base.map((m) => (m.id === assistantId ? { ...m, toolResults: edit(m.toolResults ?? []) } : m));
      });
    };

    // T-CREATOR-CREDITS-V2 (SPEC.md §9.2, F3/K-27) — ONE Idempotency-Key per user message,
    // minted here so a future retry path can resend this SAME key instead of double-charging.
    const turnIdempotencyKey = newIdempotencyKey('turn-idem');

    meeraApi
      .sendTurn(conversationId, text, 'creator', {
        // A button turn is charged as the action and is never read aloud (no voice credit is
        // taken for it), so it never asks for a voice reply either.
        voiceReply: currentAction ? false : voiceEnabled,
        idempotencyKey: turnIdempotencyKey,
        ...(currentAction ? { action: currentAction } : {}),
      })
      .then((turnRes) => {
        // Review finding #6 — creditsRemaining is `number | null` on the wire type.
        if (turnRes.creditsRemaining != null) {
          credits.applyCreditsRemaining(turnRes.creditsRemaining);
        }
        void credits.refresh();

        if (turnRes.reply != null) {
          const blank = turnRes.reply.trim() === '';
          const replyText = blank ? BLANK_REPLY_TEXT : turnRes.reply;
          // Non-streaming reply — the text is already final, so (PHASE-C-SPEC.md §3) it is parsed
          // immediately rather than waiting for a stream `onDone` this path never opens. A blank
          // reply's stand-in is local only, exactly as on the streamed path: never replayed.
          setMessages((prev) => [
            ...prev,
            blank
              ? { id: assistantId, role: 'meera', text: replyText, localOnly: true }
              : { id: assistantId, role: 'meera', text: replyText, resultCard: parseResultCard(replyText) },
          ]);
          if (!currentAction) speak(replyText, language, turnRes.messageId);
          setSending(false);
          return;
        }

        stream.open(
          turnRes.streamUrl,
          turnRes.streamToken,
          {
            onToken: (event) => {
              setTurnStarted(true);
              assistantText += event.text;
              const rendered = assistantText;
              // Every other row keeps its object identity, so only this row re-renders (SPEC 3b).
              setMessages((prev) => {
                if (!bubbleAdded) {
                  bubbleAdded = true;
                  return [...prev, { id: assistantId, role: 'meera', text: rendered }];
                }
                return prev.map((m) => (m.id === assistantId ? { ...m, text: rendered } : m));
              });
            },
            /**
             * §8.3 — append a loading card. An unknown name is IGNORED with a dev-only warn: a
             * spinner for it would promise a card that can never arrive. Never thrown on.
             */
            onToolStart: (event) => {
              setTurnStarted(true);
              const name = event.name;
              if (!isCreatorToolName(name) && !isCreatorLocalToolName(name)) {
                if (import.meta.env.DEV) {
                  console.warn('[MeeraCopilotChat] ignoring unknown tool name:', name);
                }
                return;
              }
              // A LOCAL tool (get_creator_knowledge) is kept for its work-trail step only; the
              // topic from its input is what lets the step say "notes on audio".
              const pending: CreatorToolResult = isCreatorLocalToolName(name)
                ? { id: uniqueId('tool'), name, status: 'pending', topic: knowledgeTopicOf(event.input) }
                : { id: uniqueId('tool'), name, status: 'pending' };
              editToolResults((current) => [...current, pending]);
            },

            /**
             * §8.3 — REPLACE this tool's loading card rather than appending beside it. Matched on
             * the first still-`pending` entry with the same name; with none, the result is
             * appended, so it is still shown.
             */
            onToolResult: (event) => {
              const name = event.name;
              if (!isCreatorToolName(name) && !isCreatorLocalToolName(name)) {
                if (import.meta.env.DEV) {
                  console.warn('[MeeraCopilotChat] ignoring unknown tool name:', name);
                }
                return;
              }
              // A LOCAL tool's payload is Meera's reference text or its own refusal: neither is
              // for the creator, so only the status and the topic are kept.
              const resolved: CreatorToolResult = isCreatorLocalToolName(name)
                ? { id: uniqueId('tool'), name, status: event.status, topic: knowledgeTopicOf(event.data) }
                : {
                    id: uniqueId('tool'),
                    name,
                    status: event.status,
                    data: event.data,
                    errorMessage: event.status === 'error' ? toolErrorMessage(event.data) : undefined,
                  };
              editToolResults((current) => {
                const at = current.findIndex((t) => t.name === name && t.status === 'pending');
                if (at === -1) return [...current, resolved];
                const next = [...current];
                // Keep the pending entry's id so React reconciles in place instead of remounting,
                // and the topic tool_start carried when the result has none (an error payload).
                next[at] = { ...resolved, id: current[at].id, topic: resolved.topic ?? current[at].topic };
                return next;
              });
            },

            onDone: () => {
              setSending(false);
              // MEERA-CHAT-DESIGN-SPEC.md Part A — this turn's work trail collapses.
              setMessages((prev) => prev.map((m) => (m.id === assistantId ? { ...m, toolTrailDone: true } : m)));
              if (assistantText.trim() === '') {
                assistantText = BLANK_REPLY_TEXT;
                setMessages((prev) =>
                  bubbleAdded
                    ? prev.map((m) => (m.id === assistantId ? { ...m, text: assistantText, localOnly: true } : m))
                    : [...prev, { id: assistantId, role: 'meera', text: assistantText, localOnly: true }],
                );
              }
              // PHASE-C-SPEC.md §3 — parse ONLY here, now that the turn is fully finished.
              const resultCard = parseResultCard(assistantText);
              if (resultCard) {
                setMessages((prev) => prev.map((m) => (m.id === assistantId ? { ...m, resultCard } : m)));
              }
              if (!currentAction) speak(assistantText, language, turnRes.messageId);
            },
            onError: (event) => {
              setSending(false);
              if (event.code === CONSENT_ERROR_CODE) {
                onConsentRequired();
                return;
              }
              // Cap-reached carries its own friendly copy from spend_tracker.py — show it
              // verbatim; everything else goes through friendlyStreamErrorText. Either way the
              // bubble is local only: Meera never said it, so it is never replayed to her.
              const fallbackText =
                event.code === CAP_REACHED_ERROR_CODE
                  ? event.message ?? "You've reached your monthly Meera usage limit."
                  : friendlyStreamErrorText(event.code, event.message);
              setMessages((prev) =>
                prev.some((m) => m.id === assistantId)
                  ? prev.map((m) =>
                      m.id === assistantId ? { ...m, text: fallbackText, toolTrailDone: true, localOnly: true } : m,
                    )
                  : [...prev, { id: assistantId, role: 'meera', text: fallbackText, localOnly: true }],
              );
            },
            onHeartbeatTimeout: () => {
              // Fix round 1, Q4 item (1): a hung (not dead) Python process never fires
              // onError/onDone, so without this the panel sat in `sending=true` forever.
              stream.close();
              setSending(false);
              const timeoutText = 'Meera stopped responding — try again?';
              setMessages((prev) =>
                prev.some((m) => m.id === assistantId)
                  ? prev.map((m) =>
                      m.id === assistantId ? { ...m, text: timeoutText, toolTrailDone: true, localOnly: true } : m,
                    )
                  : [...prev, { id: assistantId, role: 'meera', text: timeoutText, localOnly: true }],
              );
            },
          },
          {
            workspace_id: turnRes.workspaceId ?? '',
            conversation_id: conversationId,
            turn_id: turnRes.messageId,
            onbehalf_jwt: turnRes.onBehalfToken ?? '',
            // EV-044: only the recent part of the transcript goes to the model (lib/meera-history),
            // and never a row Meera did not say (buildModelHistory).
            conversation,
          },
        );
      })
      .catch((err: unknown) => {
        setSending(false);
        if (isConsentRequiredError(err)) {
          onConsentRequired();
          return;
        }

        // T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F7/A47/R6) — a 402/429 refusal renders the server's
        // own message as a Meera bubble plus a `BuyCreditsCard` directly under it.
        if (err instanceof ApiError && isCreditsRefusal(err.code)) {
          setMessages((prev) => [
            ...prev,
            {
              id: uniqueId('meera-credits-refusal'),
              role: 'meera',
              text: err.message,
              creditsRefusal: true,
              localOnly: true,
            },
          ]);
          void credits.refresh();
          return;
        }

        // Cap-reached (spend_tracker.py) carries its own friendly copy — show it verbatim.
        const errorText =
          err instanceof ApiError && err.code === CAP_REACHED_ERROR_CODE
            ? err.message
            : 'Something went wrong sending that — try again?';
        setMessages((prev) => [...prev, { id: uniqueId('meera-error'), role: 'meera', text: errorText, localOnly: true }]);
      });
  };

  const statusText = isListening
    ? pickLang(language, HEADER_STATUS_LISTENING)
    : isSpeaking
      ? pickLang(language, HEADER_STATUS_SPEAKING)
      : sending
        ? pickLang(language, HEADER_STATUS_WORKING)
        : pickLang(language, HEADER_STATUS_ONLINE);

  /**
   * Round 2 QA — the Send button's own click handler, separate from `handleSend`. An empty
   * composer moves focus to the textarea instead of silently doing nothing.
   */
  const handleSendClick = () => {
    // 'Review my profile' is sent with nothing typed, so only a plain turn falls back to focus.
    if (!draft.trim() && action !== 'PROFILE_REVIEW') {
      document.getElementById(composerId)?.focus();
      return;
    }
    handleSend();
  };

  const voiceStatus: MeeraVoiceStatus = isListening ? 'listening' : isSpeaking ? 'speaking' : sending ? 'thinking' : 'idle';

  // Full screen on a phone: stop the page behind the chat from scrolling while it is open. The
  // phone test follows the media query's change events (rotation, resize), not just the mount.
  const [isPhone, setIsPhone] = React.useState(
    () => typeof window !== 'undefined' && Boolean(window.matchMedia?.(PHONE_QUERY)?.matches),
  );
  React.useEffect(() => {
    if (typeof window === 'undefined') return;
    const mql = window.matchMedia?.(PHONE_QUERY);
    if (!mql) return;
    // The initial value was read in useState; from here on only change events update it.
    const onChange = (e: MediaQueryListEvent) => setIsPhone(e.matches);
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    }
    mql.addListener?.(onChange);
    return () => mql.removeListener?.(onChange);
  }, []);
  React.useEffect(() => {
    if (!isPhone) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [isPhone]);

  // iOS keyboard (SPEC 3b): --chat-vh follows the visible viewport; while the keyboard is up on a
  // phone the trust line and quick actions step aside for the transcript.
  const { keyboardOpen } = useVisualViewportHeight(rootRef);
  const keyboardUp = isPhone && keyboardOpen;

  // Voice mode reads Meera's last reply aloud/on screen. A photo-check card's text is the machine
  // summary for the model, so it (and the placeholder) is skipped (Ash 16).
  const lastMeeraReply = React.useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role !== 'meera' || m.photoCheckPending || m.resultCard?.kind === 'photoCheck') continue;
      if (m.text.trim() !== '') return m.text;
    }
    return undefined;
  }, [messages]);

  // ---- DOM window (SPEC 3b) -----------------------------------------------------------------
  const windowStartIndex = React.useMemo(() => {
    const fallback = Math.max(0, messages.length - CHAT_RENDER_WINDOW);
    if (!firstVisibleId) return fallback;
    const at = messages.findIndex((m) => m.id === firstVisibleId);
    return at === -1 ? fallback : at;
  }, [messages, firstVisibleId]);
  const visibleMessages = React.useMemo(() => messages.slice(windowStartIndex), [messages, windowStartIndex]);
  const canShowEarlier = windowStartIndex > 0 || (olderOnServer && live && !!conversationId);
  const hasCreatorMessage = React.useMemo(() => messages.some((m) => m.role === 'creator'), [messages]);

  const measureAnchor = () => {
    const el = scrollRef.current;
    anchorRef.current = el ? { height: el.scrollHeight, top: el.scrollTop } : null;
  };

  const handleShowEarlier = async () => {
    if (windowStartIndex > 0) {
      measureAnchor();
      setFirstVisibleId(messages[Math.max(0, windowStartIndex - CHAT_RENDER_WINDOW)].id);
      return;
    }
    if (!olderOnServer || !conversationId || loadingOlder || messages.length === 0) return;
    setLoadingOlder(true);
    try {
      const older = await meeraApi.getHistoryBefore(conversationId, messages[0].id, 'creator');
      // Deploy-order guard: a Spring without `?before=` answers the NEWEST rows again. Rows already
      // on screen are dropped, and a page with nothing new ends the paging instead of prepending a
      // second copy of the transcript on every click.
      const shown = new Set(messages.map((m) => m.id));
      const fresh = older.filter((item) => !shown.has(item.id));
      setOlderOnServer(fresh.length > 0 && older.length >= MEERA_HISTORY_PAGE);
      const rows = mapHistoryItems(fresh);
      if (rows.length > 0) {
        measureAnchor();
        setMessages((prev) => [...rows, ...prev]);
        setFirstVisibleId(rows[0].id);
      }
    } catch {
      toast({ title: pickLang(language, { en: "Couldn't load earlier messages.", hi: 'पहले के मैसेज लोड नहीं हो पाए।' }) });
    } finally {
      setLoadingOlder(false);
    }
  };

  // Scroll anchor: rows added ABOVE the reader keep what they were reading in place.
  React.useLayoutEffect(() => {
    const anchor = anchorRef.current;
    const el = scrollRef.current;
    if (!anchor || !el) return;
    anchorRef.current = null;
    el.scrollTop = anchor.top + (el.scrollHeight - anchor.height);
  }, [windowStartIndex, messages]);

  const shootLang = shootLangFor(language);
  // What `openCamera` / `handleCoachAnswer` would ignore: the chat's camera controls show as
  // disabled while it is true, never as live buttons that do nothing.
  const cameraBusy = sending || checking;
  const cameraDisabled = connecting || cameraBusy || (live && !conversationId);

  // Phones (live screenshots, 2026-09-24): below `sm` the chat opens full screen like a messaging
  // app (the visible viewport's height, above the app header, page scroll locked behind it); the
  // close button returns to the page. From `sm` up it keeps its original in-page size.
  return (
    <div
      ref={rootRef}
      className="fixed inset-0 top-[var(--chat-vv-top,0px)] z-50 flex h-[var(--chat-vh,100dvh)] flex-col bg-card sm:static sm:z-auto sm:h-[32rem] sm:max-h-[75vh] sm:rounded-xl sm:border sm:border-border sm:shadow-sm"
    >
      {/* MEERA-CHAT-DESIGN-SPEC.md Part 0.1 — header on the 30% band (--meera-stage), white text. */}
      <div className="flex shrink-0 items-center justify-between bg-[var(--meera-stage)] px-4 pb-3 pt-[max(0.75rem,env(safe-area-inset-top))] text-white sm:pt-3">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          {/* Meera's presence: follows her REAL state. A soft filled core behind the orb's ring,
              with a gentle pulse that is off under `prefers-reduced-motion`. */}
          <div className="relative h-9 w-9 shrink-0" aria-hidden="true">
            <div className={cn('absolute inset-[22%] rounded-full bg-primary/70', !reduceMotion && 'animate-pulse')} />
            <div className="relative z-10 h-full w-full">
              <VoicePoweredOrb enableVoiceControl={isListening} activity={isSpeaking ? 0.7 : sending ? 0.35 : 0.08} />
            </div>
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold">Meera</p>
            {/* One line, cut with an ellipsis; the full text stays in the accessible name. */}
            <p className="flex items-center gap-1.5 truncate text-xs text-white/75" aria-live="polite" title={statusText}>
              <span className="h-2 w-2 shrink-0 rounded-full bg-[#5DCAA5]" aria-hidden="true" />
              <span className="truncate">{statusText}</span>
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          <CreditBalancePill balance={credits.balance} language={language} onClick={() => setBuySheetOpen(true)} />
          {voiceInputSupported && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-11 w-11 text-white/90 hover:bg-white/10 hover:text-white"
              aria-label="Voice mode"
              title="Voice mode"
              onClick={openVoiceMode}
              disabled={connecting || camera !== null}
            >
              <AudioLines className="h-4 w-4" />
            </Button>
          )}
          {voiceOutputSupported && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="h-11 w-11 text-white/90 hover:bg-white/10 hover:text-white"
              title={voiceEnabled ? 'Voice replies on' : 'Voice replies off'}
              aria-label={voiceEnabled ? 'Voice replies on' : 'Voice replies off'}
              onClick={() => setVoiceEnabled(!voiceEnabled)}
            >
              {voiceEnabled ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
            </Button>
          )}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-11 w-11 text-white/90 hover:bg-white/10 hover:text-white"
            onClick={onClose}
            aria-label="Close"
            title="Close"
          >
            <X className="h-4 w-4" />
          </Button>
        </div>
      </div>

      <div className="relative flex min-h-0 flex-1 flex-col">
        <div
          ref={scrollRef}
          onScroll={stick.onScroll}
          data-testid="chat-scroll"
          className="flex-1 space-y-3 overflow-y-auto overscroll-contain px-4 py-4"
        >
          {canShowEarlier && (
            <div className="flex justify-center">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="min-h-11 text-xs"
                onClick={() => void handleShowEarlier()}
                disabled={loadingOlder}
                data-testid="show-earlier"
              >
                {loadingOlder ? <Loader2 className="h-3 w-3 animate-spin" /> : null}
                {pickLang(language, SHOW_EARLIER)}
              </Button>
            </div>
          )}
          {connecting && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              Connecting to Meera…
            </div>
          )}
          {connectError && (
            <div className="flex flex-col items-start gap-2">
              <p className="text-sm text-destructive-foreground">{connectError}</p>
              <Button type="button" variant="outline" size="sm" onClick={handleRetryConnect}>
                Try again
              </Button>
            </div>
          )}
          {visibleMessages.map((m) => (
            <MeeraMessageRow
              key={m.id}
              message={m}
              language={language}
              isLivePhotoCheck={m.id === liveCheckId}
              livePhoto={m.id === liveCheckId ? livePhoto : null}
              photoCheckBusy={
                m.resultCard?.kind === 'photoCheck' || m.resultCard?.kind === 'script' ? cameraBusy : false
              }
              creditsBalance={m.creditsRefusal ? credits.balance : null}
              onPrefill={prefillComposer}
              onToggleRaw={handleToggleRaw}
              onCheckShot={cameraSupported ? handleCheckShot : undefined}
              onCoachAnswer={handleCoachAnswer}
              onPhotoRetake={handlePhotoRetake}
              onCredited={handleCredited}
            />
          ))}

          {/* Part B — "Meera is on it" desk, in place of the empty screen. Shown only until the
              creator sends their first message THIS session. */}
          {!connecting && !connectError && !hasCreatorMessage && <MeeraDesk language={language} onPrefill={prefillComposer} />}

          {/* Part A — before the first tool event AND before the first token, one placeholder step. */}
          {sending && !turnStarted && (
            <div className="flex items-center gap-2 text-xs text-muted-foreground" aria-live="polite" data-testid="trail-understanding">
              <Loader2 className="h-3 w-3 animate-spin text-primary" />
              {pickLang(language, TRAIL_UNDERSTANDING)}
            </div>
          )}
        </div>

        {/* New content arrived while the reader was scrolled up: a pill that jumps down, instead
            of yanking them there (SPEC 3b). */}
        <div aria-live="polite" className="pointer-events-none absolute inset-x-0 bottom-3 flex justify-center">
          {stick.showPill ? (
            <Button
              type="button"
              size="sm"
              className="pointer-events-auto h-11 rounded-full px-4 shadow-md"
              onClick={stick.scrollToBottom}
              data-testid="new-messages-pill"
            >
              {pickLang(language, NEW_MESSAGES)}
              <ArrowDown className="h-4 w-4" aria-hidden="true" />
            </Button>
          ) : null}
        </div>
      </div>

      {/* Part 0.4 — trust line, under the messages area (steps aside while the phone keyboard is up). */}
      {!keyboardUp && (
        <div className="flex shrink-0 items-center gap-1.5 border-t border-border px-4 py-2 text-xs text-muted-foreground">
          <Lock className="h-3 w-3 shrink-0" aria-hidden="true" />
          {pickLang(language, TRUST_LINE)}
        </div>
      )}

      <div className="shrink-0 border-t border-border px-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] pt-3">
        {/* T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F9) — proactive zero-balance banner and the per-turn
            voice-cost hint, shown only while a voice reply is actually going to be requested. */}
        <ZeroCreditsBanner language={language} balance={credits.balance} onCredited={handleCredited} className="mb-2" />
        {!keyboardUp && (
          <MeeraQuickActions
            language={language}
            balance={credits.balance}
            active={action}
            onPick={setAction}
            onAnalyseBrief={onAnalyseBrief}
            disabled={connecting || sending || checking}
            className="mb-1.5"
          />
        )}
        {action ? (
          <MeeraActionStrip
            action={action}
            language={language}
            balance={credits.balance}
            onCancel={() => setAction(null)}
            onBuy={() => setBuySheetOpen(true)}
            className="mb-1.5"
          />
        ) : voiceEnabled && credits.enabled ? (
          <CreditCostHint variant="voice" language={language} className="mb-1.5" />
        ) : null}
        <div className="flex items-end gap-2">
          {cameraSupported && (
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="h-11 w-11 shrink-0"
              title={pickLang(language, CAMERA_BUTTON)}
              aria-label={pickLang(language, CAMERA_BUTTON)}
              onClick={openComposerCamera}
              disabled={cameraDisabled}
              data-testid="composer-camera"
            >
              <Camera className="h-4 w-4" />
            </Button>
          )}
          <Textarea
            id={composerId}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder={
              action === 'SCRIPT'
                ? creditsCopy('action.scriptPlaceholder', language)
                : action === 'PROFILE_REVIEW'
                  ? creditsCopy('action.profilePlaceholder', language)
                  : pickLang(language, COMPOSER_PLACEHOLDER)
            }
            rows={1}
            // 16px on phones (iOS zooms the page on focus for smaller inputs), 14px from sm up;
            // grows to 8rem, then scrolls inside itself.
            className="max-h-32 min-h-11 resize-none overflow-y-auto text-base focus-visible:border-primary focus-visible:ring-primary/50 sm:text-sm"
            disabled={connecting}
          />
          {voiceInputSupported && (
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="h-11 w-11 shrink-0"
              title={isListening ? 'Stop recording' : 'Speak your question'}
              aria-label={isListening ? 'Stop recording' : 'Speak your question'}
              onClick={isListening ? stopListening : startListening}
              disabled={connecting}
            >
              {isListening ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
            </Button>
          )}
          {/* Round 2 QA — the send button stays solid primary at full opacity; "empty" and
              "sending" are handled inside `handleSendClick`/`handleSend` instead. A running photo
              check does disable it (Ash 13): a turn sent mid-check would be stored out of order. */}
          <Button
            type="button"
            size="icon"
            className="h-11 w-11 shrink-0"
            onClick={handleSendClick}
            disabled={
              connecting ||
              checking ||
              (action !== null && actionBlockedReason(action, credits.balance, language) !== null)
            }
            aria-label="Send message"
            title="Send message"
          >
            {sending ? <Loader2 data-testid="send-spinner" className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      <MeeraVoiceMode
        open={voiceModeOpen}
        onOpenChange={handleVoiceModeChange}
        status={voiceStatus}
        transcript={draft}
        lastReply={lastMeeraReply}
        micSupported={voiceInputSupported}
        onMicToggle={isListening ? stopListening : startListening}
        onSend={handleSend}
        onSpeakAgain={() => {
          setDraft('');
          startListening();
        }}
        sendDisabled={connecting || sending || checking}
        language={language}
      />

      {/* Photo check camera (SPEC 1b). Mounted only while open, so closing it always runs the
          camera hook's full teardown (tracks stopped, wake lock released). */}
      {camera ? (
        <MeeraCameraSheet
          open
          onOpenChange={(open) => {
            if (!open) setCamera(null);
          }}
          lang={shootLang}
          shots={camera.shots}
          initialShotIndex={camera.initialShotIndex}
          onCapture={handleCapture}
        />
      ) : null}

      {/* T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F6/F8) — both stay closed/null with the flag off. */}
      <BuyCreditsSheet
        open={buySheetOpen}
        onOpenChange={setBuySheetOpen}
        language={language}
        balance={credits.balance}
        onCredited={handleCredited}
      />
      <WelcomeCreditsModal language={language} balance={credits.balance} />
    </div>
  );
}

export default MeeraCopilotChat;
