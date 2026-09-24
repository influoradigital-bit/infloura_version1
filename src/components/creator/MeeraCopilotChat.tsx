import * as React from 'react';
import { useReducedMotion } from 'framer-motion';
import { Send, Mic, MicOff, Volume2, VolumeX, X, Loader2, AudioLines, Lock } from 'lucide-react';

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
import { isCreatorToolName, meeraApi, type CreatorToolName } from '@/lib/meera-api';
import { CreatorToolResultRenderer } from '@/components/creator/meera/CreatorToolResultRenderer';
import { MeeraWorkTrail } from '@/components/creator/meera/MeeraWorkTrail';
import { MeeraDesk } from '@/components/creator/meera/MeeraDesk';
import { MeeraScriptCard } from '@/components/creator/meera/MeeraScriptCard';
import { MeeraReviewCard } from '@/components/creator/meera/MeeraReviewCard';
import { uniqueId } from '@/lib/unique-id';
import {
  parseMeeraReview,
  parseMeeraScript,
  type ParsedMeeraReview,
  type ParsedMeeraScript,
} from '@/lib/meera-result-cards';
import { useMeeraStream } from '@/hooks/useMeeraStream';
import { useVoiceOutput } from '@/hooks/useVoiceOutput';
import { useVoiceInput } from '@/hooks/useVoiceInput';
import { VoicePoweredOrb } from '@/components/ui/voice-powered-orb';
import { MeeraVoiceMode, type MeeraVoiceStatus } from '@/components/creator/meera/MeeraVoiceMode';
import { useToast } from '@/hooks/use-toast';
import { useCreatorCredits } from '@/hooks/useCreatorCredits';
import { CreditBalancePill } from '@/components/creator/credits/CreditBalancePill';
import { BuyCreditsSheet } from '@/components/creator/credits/BuyCreditsSheet';
import { BuyCreditsCard } from '@/components/creator/credits/BuyCreditsCard';
import { WelcomeCreditsModal } from '@/components/creator/credits/WelcomeCreditsModal';
import { CreditCostHint } from '@/components/creator/credits/CreditCostHint';
import { ZeroCreditsBanner } from '@/components/creator/credits/ZeroCreditsBanner';
import { creditsCopy } from '@/lib/copy/creator-credits';
import { MeeraActionStrip, MeeraQuickActions } from '@/components/creator/meera/MeeraQuickActions';
import { actionBlockedReason } from '@/lib/creator-quick-actions';
import type { CreatorTurnAction } from '@/lib/meera-api';
import {
  COMPOSER_PLACEHOLDER,
  HEADER_STATUS_LISTENING,
  HEADER_STATUS_ONLINE,
  HEADER_STATUS_SPEAKING,
  HEADER_STATUS_WORKING,
  RESULT_CARD_SHOW_AS_CARD,
  RESULT_CARD_SHOW_AS_TEXT,
  TRAIL_UNDERSTANDING,
  TRUST_LINE,
  pickLang,
} from '@/lib/copy/meera-chat';

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
 */

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.3) — one of Meera's tool calls, captured against the
 * assistant turn it belongs to so it renders inline right after that message.
 *
 * `'pending'` is the loading state `onToolStart` appends; `onToolResult` REPLACES that same entry
 * in place rather than appending a second one. Only `'ok'`/`'error'` entries are handed to
 * {@link CreatorToolResultRenderer}, whose `status` prop is that two-value union.
 */
interface CreatorToolResult {
  id: string;
  name: CreatorToolName;
  status: 'pending' | 'ok' | 'error';
  data?: unknown;
  errorMessage?: string;
}

/**
 * PHASE-C-SPEC.md §2/§3 — the parsed shape of a finished-card message, or `undefined` for every
 * normal reply. A union keyed on `kind` rather than two optional fields, so a message can never
 * carry both (or neither with a truthy discriminant) by construction.
 */
type ChatMessageResultCard =
  | { kind: 'script'; script: ParsedMeeraScript }
  | { kind: 'review'; review: ParsedMeeraReview };

interface ChatMessage {
  id: string;
  role: 'meera' | 'creator';
  text: string;
  /** LIVE-only — never set in mock mode, which opens no stream and so sees no tool events. */
  toolResults?: CreatorToolResult[];
  /**
   * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F7) — true on the bubble created for a
   * `CREATOR_CREDITS_EXHAUSTED`/`CREATOR_DAILY_CAP_REACHED` refusal, so the render below attaches
   * a `BuyCreditsCard` right under it (R6 — the chat never just goes silent on a refusal).
   */
  creditsRefusal?: boolean;
  /**
   * MEERA-CHAT-DESIGN-SPEC.md Part A — true once THIS turn's stream has finished
   * (`onDone`/`onError`/`onHeartbeatTimeout`), which is when `MeeraWorkTrail` collapses its live
   * list into the "Meera did N things · Show" summary line. Undefined for every message that
   * isn't a live, in-flight assistant turn (history rows, the mock-mode echo, the local
   * greeting) — `MeeraWorkTrail` never renders anything for those anyway, since none of them
   * carry `toolResults`.
   */
  toolTrailDone?: boolean;
  /**
   * PHASE-C-SPEC.md §3 — set only for a `meera` message whose FINAL text parsed as a script or a
   * review (`parseResultCard` below). Never set from partial/streaming text — see the `onDone`
   * handler, the only place a live turn's card is computed.
   */
  resultCard?: ChatMessageResultCard;
  /** PHASE-C-SPEC.md §3 — "Show as text" toggle state for a message that has a `resultCard`.
   *  `m.text` itself is never touched by this: the card renders while false, the ORIGINAL text
   *  renders unchanged while true. Undefined/false is "show the card". */
  showRawText?: boolean;
}

/**
 * PHASE-C-SPEC.md §1/§3 — tries the script contract, then the review contract, on a FINISHED
 * message's text. Returns `undefined` (never a partial object) the instant either parser does,
 * so the caller's fallback is always "render the plain bubble", exactly as if this function did
 * not exist.
 */
/** What mock mode answers with when there is no backend to talk to. */
const MOCK_MODE_REPLY = 'This is mock mode — connect a live backend to chat with Meera.';

function parseResultCard(text: string): ChatMessageResultCard | undefined {
  const script = parseMeeraScript(text);
  if (script) return { kind: 'script', script };
  const review = parseMeeraReview(text);
  if (review) return { kind: 'review', review };
  return undefined;
}

/**
 * What to say while a tool is still running. Typed `Record<CreatorToolName, string>` on purpose:
 * adding a seventh name to `CREATOR_TOOL_NAMES` (Phase B1/B7 add `send_routine_reply`,
 * `rank_open_campaigns`, `draft_application`) becomes a compile error here until it has a label,
 * rather than silently falling back to something vague.
 */
const TOOL_PENDING_LABELS: Record<CreatorToolName, string> = {
  get_my_deals: 'Looking up your deals…',
  get_brief: 'Reading that brief…',
  estimate_my_rate: 'Working out a rate…',
  get_my_metrics: 'Pulling your metrics…',
  check_deal_risks: 'Checking this deal…',
  draft_reply: 'Drafting a reply…',
};

/**
 * The human-readable reason out of an ERROR `tool_result` payload. The Python loop yields
 * `{error: <code>, message: <text>}` on a Spring/mesh failure, and without this the card can only
 * show its own generic sentence — masking the actual cause (auth/mesh/scope) exactly the way the
 * brand panel's `toolErrorMessage` was written to stop doing.
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
 *  sent to the model as history — purely a "someone's home" placeholder while the backend's
 *  own day-one onboarding turn (SPEC.md §4.7) lands on the wire. */
function onboardingGreeting(firstName: string, language: string): string {
  return language.startsWith('hi')
    ? `नमस्ते ${firstName}! मैं Meera हूं, Influora पर आपकी मैनेजर। मैं आपकी डील्स, कमाई और मेट्रिक्स समझने में मदद कर सकती हूं। आप क्या जानना चाहेंगे?`
    : `Hi ${firstName}! I'm Meera, your manager here on Influora. I can help you track your deals, understand your earnings, and answer questions about the platform. What would you like to know?`;
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
}

export function MeeraCopilotChat({
  firstName,
  language,
  onClose,
  onConsentRequired,
  prefillMessage,
  onAnalyseBrief,
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
   * shows the one placeholder trail step "Understanding your question…" (replacing the old
   * plain "Thinking…" line) — see the render below. Reset to false at the start of every send.
   */
  const [turnStarted, setTurnStarted] = React.useState(false);
  const [draft, setDraft] = React.useState('');
  // 2026-09-22 — a quick-action button the chat box is currently in ("Write a script" /
  // "Review my profile"). The next Send goes out as that action and is charged as it.
  const [action, setAction] = React.useState<CreatorTurnAction | null>(null);
  const scrollRef = React.useRef<HTMLDivElement>(null);
  // Round 2 QA — a stable id for the composer `<Textarea>` so `handleSendClick` can focus it
  // without needing `Textarea` (a shared shadcn primitive, `components/ui/textarea.tsx`) to
  // forward a ref, which it does not do today.
  const composerId = React.useId();
  const reduceMotion = useReducedMotion();
  const stream = useMeeraStream();
  const { toast } = useToast();

  // T-CREATOR-CREDITS-V2 (SPEC.md §9.3) — the ONE `GET /creator/credits` fetch for this panel.
  // `credits.enabled` gates every credits-UI piece below; with the server flag off (or the fetch
  // simply hasn't resolved yet) none of it renders and this component behaves exactly as it did
  // before this ticket.
  const credits = useCreatorCredits();
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
              if (history.length > 0) {
                setMessages(
                  history.map((m) => {
                    const role: ChatMessage['role'] = m.role === 'ASSISTANT' ? 'meera' : 'creator';
                    // History text is always complete (never a streaming partial), so it is safe
                    // to parse immediately rather than deferring to an `onDone` this row has no
                    // stream for.
                    return { id: m.id, role, text: m.content, resultCard: role === 'meera' ? parseResultCard(m.content) : undefined };
                  }),
                );
              } else {
                // Backend day-one onboarding hasn't sent a first turn yet (or this build predates
                // it) — show a local, non-persisted greeting so the panel never opens blank.
                setMessages([{ id: uniqueId('meera-greet'), role: 'meera', text: onboardingGreeting(firstName, language) }]);
              }
            })
            .catch(() => {
              setMessages([{ id: uniqueId('meera-greet'), role: 'meera', text: onboardingGreeting(firstName, language) }]);
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
    [firstName, language, onConsentRequired],
  );

  React.useEffect(() => {
    if (!live) {
      setConnecting(false);
      setMessages([{ id: uniqueId('meera-mock'), role: 'meera', text: onboardingGreeting(firstName, language) }]);
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
  // the caller (new `prefillMessage` object, same token) from re-firing; it does NOT by itself
  // stop two DIFFERENT tokens carrying the same text from both appending (a fast double-click on
  // "Ask Meera" resolves as two distinct requests) — the effect's own text-suffix check (PRIYA-
  // LASTCALL-U3-U5-0917.md, U-5 LOW (c)) is what makes a back-to-back repeat of the same prompt a
  // no-op instead of appending it twice.
  const appliedPrefillTokenRef = React.useRef<number | null>(null);
  React.useEffect(() => {
    if (!prefillMessage || prefillMessage.token === appliedPrefillTokenRef.current) return;
    appliedPrefillTokenRef.current = prefillMessage.token;
    setDraft((prev) => {
      // PRIYA-LASTCALL-U3-U5-0917.md, U-5 LOW (c) — the page's own consent re-probe is async, so
      // a fast double-click on "Ask Meera" produces two DISTINCT tokens (each request increments
      // the counter) before the first click's own effect has run, and both carry the same
      // prompt text. Token equality alone (the check above) does not catch that, since the two
      // tokens are genuinely different. Skip re-appending when the composer already ends with
      // this exact text, rather than doubling it up.
      if (prev.endsWith(prefillMessage.text)) return prev;
      return prev.trim() ? `${prev} ${prefillMessage.text}` : prefillMessage.text;
    });
  }, [prefillMessage]);

  React.useEffect(() => {
    const el = scrollRef.current;
    // Feature-detect rather than assume `scrollTo` exists — jsdom (this repo's test DOM) has no
    // implementation, and an unguarded call throws inside the effect, uncaught, on every render
    // that touches this panel (this is how F-0324-shaped bugs get missed: it only ever throws in
    // an environment nobody currently asserts against).
    if (el && typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior: reduceMotion ? 'auto' : 'smooth' });
    }
  }, [messages, sending, reduceMotion]);

  React.useEffect(() => {
    return () => stopSpeaking();
  }, [stopSpeaking]);

  /**
   * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F8) — the monthly-grant and 7-day paid-expiry toasts.
   * Both are "at most once" guards backed by `localStorage` (try/catch — same discipline as
   * `WelcomeCreditsModal`'s seen-marker): the monthly one keyed by `monthly.period` (a fresh
   * period is a fresh toast, forever, one per period), the expiry one keyed by the IST calendar
   * day so a creator who has the panel open across a day boundary sees at most one per day, not
   * one per fetch.
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
   * all go through this: fill the composer, never send (R-U1, same rule the `prefillMessage`
   * prop already follows). Appends to whatever is already typed rather than overwriting it, for
   * the same reason the `prefillMessage` effect above does.
   */
  const prefillComposer = React.useCallback((text: string) => {
    setDraft((prev) => (prev.trim() ? `${prev} ${text}` : text));
  }, []);

  const handleSend = () => {
    const typed = draft.trim();
    const currentAction = action;
    if (sending) return;
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
    setMessages((prev) => [...prev, { id: uniqueId('creator'), role: 'creator', text }]);

    if (!live) {
      // Mock mode — no backend to talk to. Echo a short, honest placeholder rather than a
      // scripted brand-shaped conversation this panel has no script for.
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

    /**
     * §8.3 — edit this turn's tool-result list, creating the assistant bubble FIRST if the stream
     * has not produced a token yet.
     *
     * The lazy create is the whole point: a `tool_start`/`tool_result` can legitimately arrive
     * before any token (the model calls a tool before it narrates anything), and the bubble is
     * only born in `onToken`. Without this the card would have no message to attach to and would
     * be dropped silently. Pattern copied from the brand panel's `onToolResult`
     * (`components/feature/meera/MeeraChatPanel.tsx`), which solves exactly this — formatting
     * deliberately not copied, see the fork note at the top of this file.
     *
     * `bubbleAdded` is assigned inside the updater because `exists` is only knowable there. The
     * assignment is idempotent, so a double-invoked updater (StrictMode) is harmless.
     */
    const editToolResults = (edit: (current: CreatorToolResult[]) => CreatorToolResult[]) => {
      setMessages((prev) => {
        const exists = prev.some((m) => m.id === assistantId);
        const base = exists
          ? prev
          : [...prev, { id: assistantId, role: 'meera' as const, text: assistantText }];
        if (!exists) bubbleAdded = true;
        return base.map((m) =>
          m.id === assistantId ? { ...m, toolResults: edit(m.toolResults ?? []) } : m,
        );
      });
    };

    // T-CREATOR-CREDITS-V2 (SPEC.md §9.2, F3/K-27) — ONE Idempotency-Key per user message,
    // minted here (not inside `meeraApi.sendTurn`, whose own default would mint a fresh one every
    // call). Nothing in this panel retries a send today, so there is currently only ever one POST
    // per key — but the key still has to be minted at the call site and handed to `sendTurn`
    // rather than left to its default, so that if/when a retry path is added here it can resend
    // this SAME key instead of double-charging the creator for one logical turn.
    const turnIdempotencyKey =
      typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : uniqueId('turn-idem');

    meeraApi
      .sendTurn(conversationId, text, 'creator', {
        // A button turn is charged as the action and is never read aloud (no voice credit is
        // taken for it), so it never asks for a voice reply either.
        voiceReply: currentAction ? false : voiceEnabled,
        idempotencyKey: turnIdempotencyKey,
        ...(currentAction ? { action: currentAction } : {}),
      })
      .then((turnRes) => {
        // Review finding #6 — creditsRemaining is `number | null` on the wire type (the server's
        // Integer is nullable); today it is always a number (0 when the flag is off), but a null
        // guard here keeps a future server regression from crashing the optimistic pill update
        // instead of just skipping it (the trailing credits.refresh() below still corrects it).
        if (turnRes.creditsRemaining != null) {
          credits.applyCreditsRemaining(turnRes.creditsRemaining);
        }
        void credits.refresh();

        if (turnRes.reply != null) {
          const replyText = turnRes.reply.trim() || "Sorry, I lost my train of thought there. Say that again?";
          // Non-streaming reply — the text is already final, so (PHASE-C-SPEC.md §3) it is parsed
          // immediately rather than waiting for a stream `onDone` this path never opens.
          setMessages((prev) => [
            ...prev,
            { id: assistantId, role: 'meera', text: replyText, resultCard: parseResultCard(replyText) },
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
              setMessages((prev) => {
                if (!bubbleAdded) {
                  bubbleAdded = true;
                  return [...prev, { id: assistantId, role: 'meera', text: rendered }];
                }
                return prev.map((m) => (m.id === assistantId ? { ...m, text: rendered } : m));
              });
            },
            /**
             * §8.3 — append a loading card. An unknown name is IGNORED with a dev-only warn: it
             * is a tool this build has no card for (a newer AI-service build, or a brand tool
             * leaking onto a creator stream), and a spinner for it would promise a card that can
             * never arrive. Never thrown on — a stray tool name must not take the chat down.
             *
             * A KNOWN name whose card has not been built yet (`draft_reply`, B0-49 — `get_brief`,
             * B0-44, is wired up as of U-4) deliberately DOES get its spinner, which then resolves
             * to nothing. The gate here is `isCreatorToolName` and nothing narrower on purpose: a second,
             * hand-maintained "names that have a card" list would silently stop rendering a card
             * the day someone added one to the renderer's switch and forgot this file — the exact
             * failure mode this ticket exists to fix.
             */
            onToolStart: (event) => {
              setTurnStarted(true);
              if (!isCreatorToolName(event.name)) {
                if (import.meta.env.DEV) {
                  console.warn('[MeeraCopilotChat] ignoring unknown tool name:', event.name);
                }
                return;
              }
              const name = event.name;
              const pending: CreatorToolResult = { id: uniqueId('tool'), name, status: 'pending' };
              editToolResults((current) => [...current, pending]);
            },

            /**
             * §8.3 — REPLACE this tool's loading card rather than appending beside it. Matched on
             * the first still-`pending` entry with the same name, so a turn that calls the same
             * tool twice resolves the older spinner first and never leaves one spinning forever.
             * With no pending entry to replace (a `tool_result` with no preceding `tool_start`)
             * the result is appended, so it is still shown.
             */
            onToolResult: (event) => {
              if (!isCreatorToolName(event.name)) {
                if (import.meta.env.DEV) {
                  console.warn('[MeeraCopilotChat] ignoring unknown tool name:', event.name);
                }
                return;
              }
              const name = event.name;
              const resolved: CreatorToolResult = {
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
                // Keep the pending entry's id so React reconciles in place instead of remounting.
                next[at] = { ...resolved, id: current[at].id };
                return next;
              });
            },

            onDone: () => {
              setSending(false);
              // MEERA-CHAT-DESIGN-SPEC.md Part A — this turn's stream is finished, so its work
              // trail (if it has one) collapses to the summary line. A no-op when the bubble
              // was never created (no tokens, no tool calls at all).
              setMessages((prev) =>
                prev.map((m) => (m.id === assistantId ? { ...m, toolTrailDone: true } : m)),
              );
              if (assistantText.trim() === '') {
                assistantText = "Sorry, I lost my train of thought there. Say that again?";
                setMessages((prev) =>
                  bubbleAdded
                    ? prev.map((m) => (m.id === assistantId ? { ...m, text: assistantText } : m))
                    : [...prev, { id: assistantId, role: 'meera', text: assistantText }],
                );
              }
              // PHASE-C-SPEC.md §3 — parse ONLY here, now that the turn is fully finished. Never
              // on `onToken`'s partial text, or a half-written script would flash a broken card
              // before settling into its real shape.
              const resultCard = parseResultCard(assistantText);
              if (resultCard) {
                setMessages((prev) =>
                  prev.map((m) => (m.id === assistantId ? { ...m, resultCard } : m)),
                );
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
              // verbatim instead of a generic failure message. Everything else (including the
              // transport-level CONNECTION_ERROR / STREAM_INCOMPLETE codes from useMeeraStream's
              // `fail()`) goes through friendlyStreamErrorText rather than the raw event.message.
              const fallbackText =
                event.code === CAP_REACHED_ERROR_CODE
                  ? event.message ?? "You've reached your monthly Meera usage limit."
                  : friendlyStreamErrorText(event.code, event.message);
              setMessages((prev) =>
                prev.some((m) => m.id === assistantId)
                  ? prev.map((m) =>
                      m.id === assistantId ? { ...m, text: fallbackText, toolTrailDone: true } : m,
                    )
                  : [...prev, { id: assistantId, role: 'meera', text: fallbackText }],
              );
            },
            onHeartbeatTimeout: () => {
              // Fix round 1, Q4 item (1): a hung (not dead) Python process — SIGSTOP, GC stall,
              // wedged provider socket — never fires onError/onDone, so without this the panel
              // sat in `sending=true` forever with no message and no way out but reload.
              stream.close();
              setSending(false);
              const timeoutText = 'Meera stopped responding — try again?';
              setMessages((prev) =>
                prev.some((m) => m.id === assistantId)
                  ? prev.map((m) =>
                      m.id === assistantId ? { ...m, text: timeoutText, toolTrailDone: true } : m,
                    )
                  : [...prev, { id: assistantId, role: 'meera', text: timeoutText }],
              );
            },
          },
          {
            workspace_id: turnRes.workspaceId ?? '',
            conversation_id: conversationId,
            turn_id: turnRes.messageId,
            onbehalf_jwt: turnRes.onBehalfToken ?? '',
            // EV-044: only the recent part of the transcript goes to the model (see lib/meera-history).
            conversation: recentHistory(
              [
                ...messages.map((m): HistoryTurn => ({ role: m.role === 'creator' ? 'user' : 'assistant', content: m.text })),
                { role: 'user', content: text },
              ],
              CREATOR_HISTORY_MAX_TURNS,
              CREATOR_HISTORY_MAX_CHARS,
            ),
          },
        );
      })
      .catch((err: unknown) => {
        setSending(false);
        if (isConsentRequiredError(err)) {
          onConsentRequired();
          return;
        }

        // T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F7/A47/R6) — a 402/429 refusal from the credits
        // ledger renders the server's own en/hi-templated `message` as a Meera bubble (never a
        // generic failure sentence — the chat must never go silent) plus a `BuyCreditsCard`
        // directly under it, and the balance/pill is refetched so the numbers on screen agree with
        // what just refused the send.
        if (err instanceof ApiError && isCreditsRefusal(err.code)) {
          setMessages((prev) => [
            ...prev,
            { id: uniqueId('meera-credits-refusal'), role: 'meera', text: err.message, creditsRefusal: true },
          ]);
          void credits.refresh();
          return;
        }

        // Cap-reached (spend_tracker.py) carries its own friendly copy — show it verbatim.
        const text =
          err instanceof ApiError && err.code === CAP_REACHED_ERROR_CODE
            ? err.message
            : 'Something went wrong sending that — try again?';
        setMessages((prev) => [...prev, { id: uniqueId('meera-error'), role: 'meera', text }]);
      });
  };

  /**
   * Round 2 QA — the Send button's own click handler, separate from `handleSend` (which stays
   * the single source of truth for what "send" actually does, unchanged). An empty composer
   * moves focus to the textarea instead of silently doing nothing — the button is never
   * `disabled` for this case any more, so it needs its own honest response to a tap.
   */
  const statusText = isListening
    ? pickLang(language, HEADER_STATUS_LISTENING)
    : isSpeaking
      ? pickLang(language, HEADER_STATUS_SPEAKING)
      : sending
        ? pickLang(language, HEADER_STATUS_WORKING)
        : pickLang(language, HEADER_STATUS_ONLINE);

  const handleSendClick = () => {
    // "Review my profile" needs no text; every other send needs some (credits branch rule).
    if (!draft.trim() && action !== 'PROFILE_REVIEW') {
      document.getElementById(composerId)?.focus();
      return;
    }
    handleSend();
  };

  const voiceStatus: MeeraVoiceStatus = isListening ? 'listening' : isSpeaking ? 'speaking' : sending ? 'thinking' : 'idle';
  const lastMeeraReply = [...messages].reverse().find((m) => m.role === 'meera' && m.text.trim() !== '')?.text;

  // Live check at 375px: the fixed 32rem panel left the message area 203px tall, so the desk sat
  // below the fold behind a wrapped header. On a phone the panel takes most of the screen
  // instead; from `sm` up it keeps its original size.
  return (
    <div className="flex h-[min(78vh,32rem)] flex-col rounded-xl border border-border bg-card shadow-sm sm:h-[32rem] sm:max-h-[75vh]">
      {/* MEERA-CHAT-DESIGN-SPEC.md Part 0.1 — header on the 30% band (--meera-stage), white text. */}
      <div className="flex shrink-0 items-center justify-between bg-[var(--meera-stage)] px-4 py-3 text-white">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          {/* Meera's presence: follows her REAL state. The mic is opened by the orb only while the
              creator is already recording (useVoiceInput has the permission by then).
              Round 2 QA — at rest (low `activity`) the orb's thin ring alone read as an empty
              "grey ring" in the live render. A soft filled core (primary colour) behind it, with
              a gentle pulse that is off under `prefers-reduced-motion`, makes it read as alive.
              Header only — the big hero orb (MeeraHero.tsx) is untouched. */}
          <div className="relative h-9 w-9 shrink-0" aria-hidden="true">
            <div
              className={cn(
                'absolute inset-[22%] rounded-full bg-primary/70',
                !reduceMotion && 'animate-pulse',
              )}
            />
            <div className="relative z-10 h-full w-full">
              <VoicePoweredOrb
                enableVoiceControl={isListening}
                activity={isSpeaking ? 0.7 : sending ? 0.35 : 0.08}
              />
            </div>
          </div>
          <div className="min-w-0">
            <p className="text-sm font-semibold">Meera</p>
            {/* `truncate` + `min-w-0`: at 375px this sentence wrapped to five lines and made the
                header 156px tall. One line, cut with an ellipsis; the full text stays in the
                accessible name for screen readers. */}
            <p
              className="flex items-center gap-1.5 truncate text-xs text-white/75"
              aria-live="polite"
              title={statusText}
            >
              {/* Round 2 QA — `success-foreground` failed contrast on this dark band; a bright
                  mint (≥3:1 on #221e35) at ≥8px reads clearly as an online indicator. */}
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
              disabled={connecting}
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

      <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto px-4 py-4">
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
        {messages.map((m) => (
          <div key={m.id} data-testid="chat-turn" className="space-y-1.5">
            {/* MEERA-CHAT-DESIGN-SPEC.md Part A — the work trail renders ABOVE the answer it
                belongs to, in addition to (never instead of) the tool-result cards below. Only
                ever real tool events: `m.toolResults` is exactly what `onToolStart`/`onToolResult`
                built for this turn, so a turn with none renders no trail at all. */}
            {m.role === 'meera' && (
              <MeeraWorkTrail
                steps={m.toolResults ?? []}
                done={!!m.toolTrailDone}
                language={language}
              />
            )}

            {/* A bubble is skipped while its text is empty rather than rendered as a blank pill.
                That window is real and only exists because of the lazy create above: a tool card
                can attach to this turn before the first token arrives. `onToken`/`onDone` fill the
                text in, and the bubble appears then. */}
            {m.text ? (
              m.role === 'meera' && m.resultCard && !m.showRawText ? (
                // PHASE-C-SPEC.md §3 — a finished script/review renders as its card INSTEAD of
                // the bubble. `rawText={m.text}` is the untouched original — Copy and "Show as
                // text" both read from it, never from a re-serialized version of the parsed
                // fields.
                <div className="max-w-[85%]">
                  {m.resultCard.kind === 'script' ? (
                    <MeeraScriptCard
                      script={m.resultCard.script}
                      rawText={m.text}
                      language={language}
                      onPrefill={prefillComposer}
                    />
                  ) : (
                    <MeeraReviewCard
                      review={m.resultCard.review}
                      rawText={m.text}
                      language={language}
                      onPrefill={prefillComposer}
                    />
                  )}
                </div>
              ) : (
                <div className={cn('flex items-end gap-1.5', m.role === 'creator' ? 'justify-end' : 'justify-start')}>
                  {/* Part 0.2 — Meera's bubbles get a small orb-coloured dot avatar. */}
                  {m.role === 'meera' && (
                    <span
                      className="mb-1 h-2 w-2 shrink-0 rounded-full bg-primary"
                      aria-hidden="true"
                    />
                  )}
                  <div
                    className={cn(
                      'max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm',
                      m.role === 'creator'
                        ? 'bg-primary text-primary-foreground'
                        : 'border border-border bg-white text-foreground',
                    )}
                  >
                    {m.text}
                  </div>
                </div>
              )
            ) : null}

            {/* PHASE-C-SPEC.md §3 — the toggle that reveals (or re-hides) the ORIGINAL text under
                a rendered card. `m.text` is never discarded either way, so a creator who wants to
                copy the reply exactly as written, or hear a voice reply read from it, still can. */}
            {m.role === 'meera' && m.resultCard ? (
              <button
                type="button"
                data-testid="result-card-text-toggle"
                onClick={() =>
                  setMessages((prev) =>
                    prev.map((row) => (row.id === m.id ? { ...row, showRawText: !row.showRawText } : row)),
                  )
                }
                className="text-xs font-medium text-primary hover:underline"
              >
                {pickLang(language, m.showRawText ? RESULT_CARD_SHOW_AS_CARD : RESULT_CARD_SHOW_AS_TEXT)}
              </button>
            ) : null}

            {/* T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F7) — a real Buy CTA directly under a 402/429
                refusal bubble, never just the sentence on its own (R6). */}
            {m.creditsRefusal ? (
              <BuyCreditsCard
                language={language}
                balance={credits.balance}
                onCredited={() => void credits.refresh()}
                className="ml-0 mr-auto max-w-[85%]"
              />
            ) : null}

            {/* §8.3 — tool cards render AFTER the bubble they belong to. No `onPrefillCounter` or
                `onOpenDeal` is passed: this panel has no counter form and no deal navigation to
                hand them to (both live on the deal pages, §8.6), and every card hides the matching
                control when the callback is absent. A visible button wired to nothing would be
                worse than no button. */}
            {m.toolResults?.map((tool) =>
              tool.status === 'pending' ? (
                <div
                  key={tool.id}
                  data-testid="creator-tool-pending"
                  className="flex items-center gap-2 text-xs text-muted-foreground"
                >
                  <Loader2 className="h-3 w-3 animate-spin" />
                  {TOOL_PENDING_LABELS[tool.name]}
                </div>
              ) : (
                <CreatorToolResultRenderer
                  key={tool.id}
                  toolName={tool.name}
                  status={tool.status}
                  data={tool.data}
                  errorMessage={tool.errorMessage}
                />
              ),
            )}
          </div>
        ))}

        {/* Part B — "Meera is on it" desk, in place of the empty screen. Shown only until the
            creator sends their first message THIS session; `messages.some(creator)` never goes
            back to false once true, so the desk never reappears later in the same conversation. */}
        {!connecting && !connectError && !messages.some((m) => m.role === 'creator') && (
          <MeeraDesk language={language} onPrefill={prefillComposer} />
        )}

        {/* Part A — before the first tool event AND before the first token, one placeholder step.
            Replaces the old plain "Thinking…" line. Disappears the instant a tool step or text
            arrives (`turnStarted`, flipped by `onToken`/`onToolStart` above). */}
        {sending && !turnStarted && (
          <div
            className="flex items-center gap-2 text-xs text-muted-foreground"
            aria-live="polite"
            data-testid="trail-understanding"
          >
            <Loader2 className="h-3 w-3 animate-spin text-primary" />
            {pickLang(language, TRAIL_UNDERSTANDING)}
          </div>
        )}
      </div>

      {/* Part 0.4 — trust line, under the messages area. */}
      <div className="flex shrink-0 items-center gap-1.5 border-t border-border px-4 py-2 text-xs text-muted-foreground">
        <Lock className="h-3 w-3 shrink-0" aria-hidden="true" />
        {pickLang(language, TRUST_LINE)}
      </div>

      <div className="shrink-0 border-t border-border p-3">
        {/* T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F9) — proactive zero-balance banner, above the
            composer so it never blocks reading the transcript, and the per-turn voice-cost hint,
            shown only while a voice reply is actually going to be requested. */}
        <ZeroCreditsBanner
          language={language}
          balance={credits.balance}
          onCredited={() => void credits.refresh()}
          className="mb-2"
        />
        <MeeraQuickActions
          language={language}
          balance={credits.balance}
          active={action}
          onPick={setAction}
          onAnalyseBrief={onAnalyseBrief}
          disabled={connecting || sending}
          className="mb-1.5"
        />
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
            className="min-h-11 resize-none text-sm focus-visible:border-primary focus-visible:ring-primary/50"
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
          {/* Round 2 QA — the send button must stay solid primary at full opacity at ALL times
              (the washed-out `disabled:opacity-50` look was explicitly rejected), including while
              the composer is empty and while a turn is sending. `disabled` is therefore only ever
              `connecting` (a brief, legitimate loading state, not the "everyday empty box" the
              complaint was about); "empty" and "sending" are handled inside `handleSendClick`
              instead — tapping an empty box focuses the textarea rather than doing nothing, and
              `handleSend`'s own `if (!text || sending) return;` guard (unchanged) still makes a
              tap-while-sending a no-op, so nothing here can double-send. */}
          <Button
            type="button"
            size="icon"
            className="h-11 w-11 shrink-0"
            onClick={handleSendClick}
            // Never disabled while sending (Round 2 QA: a washed-out button read as broken); handleSend
            // itself ignores a second tap. Disabled only while connecting, or when the picked quick
            // action cannot be paid for (credits branch).
            disabled={
              connecting ||
              (action !== null && actionBlockedReason(action, credits.balance, language) !== null)
            }
            aria-label="Send message"
            title="Send message"
          >
            {sending ? (
              <Loader2 data-testid="send-spinner" className="h-4 w-4 animate-spin" />
            ) : (
              <Send className="h-4 w-4" />
            )}
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
        sendDisabled={connecting || sending}
        language={language}
      />

      {/* T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F6/F8) — both render `null`/stay closed whenever
          `credits.balance` is `null`/disabled, so mounting them unconditionally here is safe with
          the flag off. */}
      <BuyCreditsSheet
        open={buySheetOpen}
        onOpenChange={setBuySheetOpen}
        language={language}
        balance={credits.balance}
        onCredited={() => void credits.refresh()}
      />
      <WelcomeCreditsModal language={language} balance={credits.balance} />
    </div>
  );
}

export default MeeraCopilotChat;
