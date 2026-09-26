import * as React from 'react';
import { Loader2 } from 'lucide-react';

import { cn } from '@/lib/utils';
import { renderMeeraText } from '@/lib/meera-text';
import {
  isCreatorToolName,
  type CreatorToolName,
  type CreatorTrailToolName,
  type MeeraShootCheckAsk,
  type MeeraShootCheckFrameResult,
} from '@/lib/meera-api';
import type { ParsedMeeraReview, ParsedMeeraScript } from '@/lib/meera-result-cards';
import type { CreatorCreditBalance } from '@/lib/api';
import { CreatorToolResultRenderer } from '@/components/creator/meera/CreatorToolResultRenderer';
import { MeeraWorkTrail } from '@/components/creator/meera/MeeraWorkTrail';
import { MeeraScriptCard } from '@/components/creator/meera/MeeraScriptCard';
import { MeeraReviewCard } from '@/components/creator/meera/MeeraReviewCard';
import { MeeraPhotoCheckMessage } from '@/components/creator/meera/MeeraPhotoCheckMessage';
import { BuyCreditsCard } from '@/components/creator/credits/BuyCreditsCard';
import type { CoachAnswer } from '@/components/creator/shoot-check/CoachResult';
import type { ShootCheckShot } from '@/components/creator/shoot-check/ShootCheckPanel';
import { RESULT_CARD_SHOW_AS_CARD, RESULT_CARD_SHOW_AS_TEXT, pickLang, type BilingualText } from '@/lib/copy/meera-chat';

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.3) — one of Meera's tool calls, captured against the
 * assistant turn it belongs to so it renders inline right after that message.
 *
 * `'pending'` is the loading state `onToolStart` appends; `onToolResult` REPLACES that same entry
 * in place rather than appending a second one. Only `'ok'`/`'error'` entries are handed to
 * {@link CreatorToolResultRenderer}, whose `status` prop is that two-value union.
 */
export interface CreatorToolResult {
  id: string;
  /**
   * A Spring-backed creator tool (gets a card) or a LOCAL one (`get_creator_knowledge`), which
   * gets only its work-trail step: no spinner row, no card, and its `data` (Influora's notes, the
   * model's reference text) is never kept, so it can never be dumped into the chat.
   */
  name: CreatorTrailToolName;
  status: 'pending' | 'ok' | 'error';
  data?: unknown;
  errorMessage?: string;
  /** `get_creator_knowledge` only: the topic looked up, for the "notes on audio" trail label. */
  topic?: string;
}

/**
 * A finished photo check shown as Meera's message (photo check in Meera's chat, SPEC 1d). Built
 * ONLY from `checkFrame`'s parsed result or from the history item's server-written `card` (SPEC
 * 2d) — never parsed from message text, so a reply that imitates the summary can never become one.
 */
export interface PhotoCheckCard {
  kind: 'photoCheck';
  result: MeeraShootCheckFrameResult;
  shotLabel?: string;
  /** The shot the check was for, so "Check again" reopens the camera on it. Absent on a card
   *  rehydrated from history (only its label survives a reload). */
  shot?: ShootCheckShot | null;
  /** The coach answers this check was sent with (the chips already tapped), for "You said". */
  answers?: CoachAnswer[];
  /** One id per PHOTO: a chip re-check keeps the first check's id. The model replay keeps only
   *  the newest check of each photo (Ash 8). */
  photoId: string;
}

/**
 * PHASE-C-SPEC.md §2/§3 — the parsed shape of a finished-card message, or `undefined` for every
 * normal reply. A union keyed on `kind` rather than optional fields, so a message can never carry
 * two cards by construction.
 */
export type ChatMessageResultCard =
  | { kind: 'script'; script: ParsedMeeraScript }
  | { kind: 'review'; review: ParsedMeeraReview }
  | PhotoCheckCard;

export interface ChatMessage {
  id: string;
  role: 'meera' | 'creator';
  text: string;
  /** LIVE-only — never set in mock mode, which opens no stream and so sees no tool events. */
  toolResults?: CreatorToolResult[];
  /**
   * T-CREATOR-CREDITS-V2 (SPEC.md §9.3, F7) — true on the bubble created for a
   * `CREATOR_CREDITS_EXHAUSTED`/`CREATOR_DAILY_CAP_REACHED` refusal, so the row attaches a
   * `BuyCreditsCard` right under it (R6 — the chat never just goes silent on a refusal).
   */
  creditsRefusal?: boolean;
  /**
   * MEERA-CHAT-DESIGN-SPEC.md Part A — true once THIS turn's stream has finished, which is when
   * `MeeraWorkTrail` collapses its live list into the "Meera did N things · Show" summary line.
   */
  toolTrailDone?: boolean;
  /**
   * PHASE-C-SPEC.md §3 — set only for a `meera` message whose FINAL text parsed as a script or a
   * review, or for a photo check (from its result, never from text).
   */
  resultCard?: ChatMessageResultCard;
  /** PHASE-C-SPEC.md §3 — "Show as text" toggle state for a script/review card. */
  showRawText?: boolean;
  /**
   * Long-chat bug 4 (SPEC 3a/3b): a row that exists only on this screen and was never said by
   * Meera on the server — the local greeting, the mock reply, a stream error or timeout, a credits
   * refusal, a photo-check placeholder or failure. It is shown but NEVER replayed to the model as
   * history (the model used to read its own "try again?" lines as things it had said).
   */
  localOnly?: true;
  /** The "Looking at your photo…" placeholder while a photo check runs. */
  photoCheckPending?: true;
  /** A creator row that started a photo check: the photo's id, so the model replay keeps only
   *  the newest check of each photo together with its own creator line. */
  photoCheckUserOf?: string;
}

/**
 * What to say while a tool is still running. Typed `Record<CreatorToolName, string>` on purpose:
 * adding a name to `CREATOR_TOOL_NAMES` becomes a compile error here until it has a label.
 */
const TOOL_PENDING_LABELS: Record<CreatorToolName, string> = {
  get_my_deals: 'Looking up your deals…',
  get_brief: 'Reading that brief…',
  estimate_my_rate: 'Working out a rate…',
  get_my_metrics: 'Pulling your metrics…',
  check_deal_risks: 'Checking this deal…',
  draft_reply: 'Drafting a reply…',
  get_todays_topics: 'Checking today’s topics…',
  plan_my_week: 'Planning your week…',
};

const PHOTO_CHECK_PENDING: BilingualText = {
  en: 'Looking at your photo…',
  hi: 'आपकी फ़ोटो देख रही हूँ…',
};

export interface MeeraMessageRowProps {
  message: ChatMessage;
  language: string;
  /** True only on the newest photo-check card: its answer chips are live. */
  isLivePhotoCheck: boolean;
  /** The newest photo-check card's photo while the chat holds it (null on every other row), for
   *  its Reel layout guide. Never stored. */
  livePhoto?: Blob | null;
  /** A photo check is running or a chat turn is streaming, so the chat would ignore a camera tap:
   *  photo-check cards disable their chips and "Check again", and a script card its "Check my
   *  set-up" buttons. The chat passes `false` to every other row so a check or a turn starting
   *  does not re-render them. */
  photoCheckBusy: boolean;
  /** Passed only to a credits-refusal row (null elsewhere, so a balance change re-renders only
   *  the rows that show it). */
  creditsBalance: CreatorCreditBalance | null;
  /** Every callback below must be referentially stable (useCallback in the chat): one inline
   *  arrow here brings back the render-every-row-per-token cost this component exists to stop. */
  onPrefill: (text: string) => void;
  onToggleRaw: (messageId: string) => void;
  /** Omitted when the device has no camera support: the script card then shows no set-up
   *  buttons. */
  onCheckShot?: (messageId: string, beatIndex: number) => void;
  onCoachAnswer: (messageId: string, ask: MeeraShootCheckAsk, option: number) => void;
  onPhotoRetake: (messageId: string) => void;
  onCredited: () => void;
}

const NO_ANSWERS: CoachAnswer[] = [];

function MeeraMessageRowImpl({
  message: m,
  language,
  isLivePhotoCheck,
  livePhoto = null,
  photoCheckBusy,
  creditsBalance,
  onPrefill,
  onToggleRaw,
  onCheckShot,
  onCoachAnswer,
  onPhotoRetake,
  onCredited,
}: MeeraMessageRowProps) {
  const id = m.id;
  const handleCheckShot = React.useMemo(
    () => (onCheckShot ? (beatIndex: number) => onCheckShot(id, beatIndex) : undefined),
    [onCheckShot, id],
  );
  const handleAnswer = React.useCallback(
    (ask: MeeraShootCheckAsk, option: number) => onCoachAnswer(id, ask, option),
    [onCoachAnswer, id],
  );
  const handleRetake = React.useCallback(() => onPhotoRetake(id), [onPhotoRetake, id]);
  const card = m.role === 'meera' ? m.resultCard : undefined;

  return (
    <div data-testid="chat-turn" data-message-id={m.id} className="space-y-1.5">
      {/* MEERA-CHAT-DESIGN-SPEC.md Part A — the work trail renders ABOVE the answer it belongs
          to. Only ever real tool events; a turn with none renders no trail at all. */}
      {m.role === 'meera' && <MeeraWorkTrail steps={m.toolResults ?? []} done={!!m.toolTrailDone} language={language} />}

      {m.photoCheckPending ? (
        <div
          className="flex items-center gap-2 text-xs text-muted-foreground"
          aria-live="polite"
          data-testid="photo-check-pending"
        >
          <Loader2 className="h-3 w-3 animate-spin text-primary" />
          {pickLang(language, PHOTO_CHECK_PENDING)}
        </div>
      ) : card?.kind === 'photoCheck' ? (
        // Photo check (SPEC 1d): the coach card is the message. Its own width is
        // `w-full sm:max-w-[85%]` — the 85% wrapper used for scripts would leave ~290px at 375px.
        <MeeraPhotoCheckMessage
          result={card.result}
          shotLabel={card.shotLabel}
          answers={card.answers ?? NO_ANSWERS}
          lang={language}
          interactive={isLivePhotoCheck}
          photo={isLivePhotoCheck ? livePhoto : null}
          busy={photoCheckBusy}
          onAnswer={handleAnswer}
          onCheckAgain={handleRetake}
        />
      ) : m.text ? (
        // A bubble is skipped while its text is empty rather than rendered as a blank pill (a
        // tool card can attach to this turn before the first token arrives).
        card && !m.showRawText ? (
          // PHASE-C-SPEC.md §3 — a finished script/review renders as its card INSTEAD of the
          // bubble. `rawText={m.text}` is the untouched original.
          <div className="max-w-[85%]">
            {card.kind === 'script' ? (
              <MeeraScriptCard
                script={card.script}
                rawText={m.text}
                language={language}
                onCheckShot={handleCheckShot}
                checkShotDisabled={photoCheckBusy}
              />
            ) : (
              <MeeraReviewCard review={card.review} rawText={m.text} language={language} onPrefill={onPrefill} />
            )}
          </div>
        ) : (
          <div className={cn('flex items-end gap-1.5', m.role === 'creator' ? 'justify-end' : 'justify-start')}>
            {/* Part 0.2 — Meera's bubbles get a small orb-coloured dot avatar. */}
            {m.role === 'meera' && <span className="mb-1 h-2 w-2 shrink-0 rounded-full bg-primary" aria-hidden="true" />}
            <div
              className={cn(
                'max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm',
                m.role === 'creator' ? 'bg-primary text-primary-foreground' : 'border border-border bg-white text-foreground',
              )}
            >
              {/* Meera's light markdown shown cleanly; the creator's own words exactly as typed. */}
              {m.role === 'meera' ? renderMeeraText(m.text) : m.text}
            </div>
          </div>
        )
      ) : null}

      {/* PHASE-C-SPEC.md §3 — the toggle that reveals (or re-hides) the ORIGINAL text under a
          script/review card. A photo check has none: its text is the machine summary for Meera. */}
      {card && card.kind !== 'photoCheck' ? (
        <button
          type="button"
          data-testid="result-card-text-toggle"
          onClick={() => onToggleRaw(id)}
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
          balance={creditsBalance}
          onCredited={onCredited}
          className="ml-0 mr-auto max-w-[85%]"
        />
      ) : null}

      {/* §8.3 — tool cards render AFTER the bubble they belong to. Only the Spring-backed tools
          get a spinner row and a card; a LOCAL tool is shown by its work-trail step only. */}
      {m.toolResults?.map((tool) =>
        !isCreatorToolName(tool.name) ? null : tool.status === 'pending' ? (
          <div key={tool.id} data-testid="creator-tool-pending" className="flex items-center gap-2 text-xs text-muted-foreground">
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
  );
}

/**
 * One chat row (long chats, SPEC 3b). Memoised: a streamed token replaces only the streaming
 * message's object (`prev.map(m => m.id === id ? {...m} : m)` keeps every other row by identity),
 * so with stable callbacks only that one row re-renders per token instead of every bubble and
 * card in the thread.
 */
export const MeeraMessageRow = React.memo(MeeraMessageRowImpl);
MeeraMessageRow.displayName = 'MeeraMessageRow';
