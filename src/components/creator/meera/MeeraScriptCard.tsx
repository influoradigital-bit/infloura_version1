import * as React from 'react';
import { Camera, Check, Copy } from 'lucide-react';

import { cn } from '@/lib/utils';
import { copyPlainText } from '@/lib/clipboard';
import {
  RESULT_CARD_COPIED,
  RESULT_CARD_COPY,
  SCRIPT_CARD_ACTION_LABEL,
  SCRIPT_CARD_BEFORE_YOU_SHOOT_LABEL,
  SCRIPT_CARD_CAPTION_LABEL,
  SCRIPT_CARD_ON_SCREEN_LABEL,
  SCRIPT_CARD_PAUSE_LABEL,
  SCRIPT_CARD_PLAN_LABEL,
  SCRIPT_CARD_STRESS_LABEL,
  SCRIPT_CARD_SUCCESS_LABEL,
  SCRIPT_CARD_WHY_LABEL,
  pickLang,
} from '@/lib/copy/meera-chat';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';

/** Label for the optional `Set-up:` line (persona 2026-09-25). Kept here rather than in
 *  `@/lib/copy/meera-chat` only because that file was outside this change's scope; move it there
 *  alongside the other SCRIPT_CARD_* labels when next touching that file. */
const SCRIPT_CARD_SETUP_LABEL = { en: 'Set-up', hi: 'सेट-अप' };

/** The per-beat photo-check button (SPEC section 1a). Same "kept here" reason as the label above. */
const SCRIPT_CARD_CHECK_SHOT_LABEL = { en: 'Check my set-up', hi: 'सेट-अप चेक करें' };

/**
 * The rich reel-script card — reads a `ParsedMeeraScript` (the "Full script format" in
 * `influora-ai/app/prompt/creator_persona.py`, via `parseMeeraScript`) and renders it as a
 * finished card. Rendered by `MeeraCopilotChat.tsx` INSTEAD of the plain bubble once a finished
 * assistant turn's text has parsed; the caller keeps the original bubble as a fallback for an
 * `undefined` parse, so this component can assume `script` is already a fully valid, non-partial
 * shape (`setup`, `successLooksLike` and `followUp` are the only fields allowed to be absent).
 *
 * Creator tokens only (`border-border`, `bg-card`, `bg-muted`, `text-primary`) — same rule
 * `CreatorToolResultRenderer.tsx` documents for the other creator-side cards. No `--meera-stage`
 * here: that dark ink is the chat HEADER's colour (`MeeraCopilotChat.tsx`), not reused elsewhere.
 */

export interface MeeraScriptCardProps {
  script: ParsedMeeraScript;
  /** The exact, unmodified assistant message text — what Copy writes and what "Show as text"
   *  reveals. Never reconstructed from `script`, so Copy is always byte-for-byte what Meera
   *  actually sent, including any language-specific phrasing `parseMeeraScript` does not retain. */
  rawText: string;
  language: string;
  className?: string;
  /** Photo check inside Meera's chat: when passed, every beat gets a "Check my set-up" button
   *  that calls this with the beat's 0-based index (the chat maps it with `shotFromBeat` and opens
   *  the camera on that shot). When absent, no button renders at all. */
  onCheckShot?: (beatIndex: number) => void;
  /** The chat is busy (a turn is streaming or a photo check is running) and would ignore a tap:
   *  the set-up buttons show as disabled instead of doing nothing. */
  checkShotDisabled?: boolean;
}

export function MeeraScriptCard({
  script,
  rawText,
  language,
  className,
  onCheckShot,
  checkShotDisabled = false,
}: MeeraScriptCardProps) {
  const beatIdPrefix = React.useId();
  const [copied, setCopied] = React.useState(false);
  const copiedTimeoutRef = React.useRef<number | null>(null);

  React.useEffect(() => {
    return () => {
      if (copiedTimeoutRef.current !== null) window.clearTimeout(copiedTimeoutRef.current);
    };
  }, []);

  // Called synchronously from the click handler (see clipboard.ts's doc comment) — required for
  // mobile Safari, which only honours navigator.clipboard.writeText in direct response to a tap.
  const handleCopy = () => {
    void copyPlainText(rawText).then((ok) => {
      if (!ok) return;
      setCopied(true);
      if (copiedTimeoutRef.current !== null) window.clearTimeout(copiedTimeoutRef.current);
      copiedTimeoutRef.current = window.setTimeout(() => setCopied(false), 2000);
    });
  };

  const onScreenLabel = pickLang(language, SCRIPT_CARD_ON_SCREEN_LABEL);
  const stressLabel = pickLang(language, SCRIPT_CARD_STRESS_LABEL);
  const pauseLabel = pickLang(language, SCRIPT_CARD_PAUSE_LABEL);
  const checkShotLabel = pickLang(language, SCRIPT_CARD_CHECK_SHOT_LABEL);

  return (
    <div
      data-testid="meera-script-card"
      className={cn('space-y-3 rounded-xl border border-border bg-card p-3', className)}
    >
      <p data-testid="script-card-idea" className="text-sm font-semibold break-words">
        {script.idea}
      </p>

      {/* Plan / Action / Set-up / Success looks like — short labelled lines, same "muted label + value"
          shape the old card used for Hook/CTA, so this stays visually consistent with
          MeeraReviewCard's Working/Not working rows. */}
      <div className="space-y-1.5">
        <p data-testid="script-card-plan" className="text-sm break-words">
          <span className="text-xs font-medium text-muted-foreground">
            {pickLang(language, SCRIPT_CARD_PLAN_LABEL)}:{' '}
          </span>
          {script.plan}
        </p>
        <p data-testid="script-card-action" className="text-sm break-words">
          <span className="text-xs font-medium text-muted-foreground">
            {pickLang(language, SCRIPT_CARD_ACTION_LABEL)}:{' '}
          </span>
          {script.action}
        </p>
        {script.setup ? (
          <p data-testid="script-card-setup" className="text-sm break-words">
            <span className="text-xs font-medium text-muted-foreground">
              {pickLang(language, SCRIPT_CARD_SETUP_LABEL)}:{' '}
            </span>
            {script.setup}
          </p>
        ) : null}
        {script.successLooksLike ? (
          <p data-testid="script-card-success" className="text-sm break-words">
            <span className="text-xs font-medium text-muted-foreground">
              {pickLang(language, SCRIPT_CARD_SUCCESS_LABEL)}:{' '}
            </span>
            {script.successLooksLike}
          </p>
        ) : null}
      </div>

      {/* Beats — one compact block per beat: the timing chip and shot description on one line,
          the exact line to say (quoted, emphasised, since it's what the creator reads out loud),
          then the on-screen overlay text as a small muted line. Stacks cleanly at 375px since
          nothing here relies on a side-by-side column. */}
      <ul className="space-y-2 border-t border-border pt-2">
        {script.beats.map((beat, index) => (
          <li key={`${beat.from}-${beat.to}-${index}`} data-testid="script-card-beat" className="text-sm">
            <p id={`${beatIdPrefix}-beat-${index}`} className="break-words">
              <span className="text-xs font-medium text-muted-foreground">
                {beat.from}-{beat.to}s ·{' '}
              </span>
              {beat.shot}
            </p>
            <p className="break-words font-medium text-primary [overflow-wrap:anywhere]">&ldquo;{beat.say}&rdquo;</p>
            {beat.stress && beat.pause ? (
              <p
                data-testid="script-card-beat-emphasis"
                className="break-words text-xs text-muted-foreground [overflow-wrap:anywhere]"
              >
                {stressLabel}: {beat.stress} · {pauseLabel}: {beat.pause}
              </p>
            ) : null}
            <p className="break-words text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {onScreenLabel}: {beat.onScreen}
            </p>
            {onCheckShot ? (
              <button
                type="button"
                data-testid="script-card-check-shot"
                // Every beat's button reads the same, so the beat's timing and shot line is
                // attached as its description: a screen reader hears which shot it checks.
                aria-describedby={`${beatIdPrefix}-beat-${index}`}
                onClick={() => onCheckShot(index)}
                disabled={checkShotDisabled}
                className="mt-1 -ml-2 inline-flex min-h-11 items-center gap-1.5 rounded-lg px-2 text-sm font-medium text-primary hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50"
              >
                <Camera className="h-4 w-4" aria-hidden="true" />
                {checkShotLabel}
              </button>
            ) : null}
          </li>
        ))}
      </ul>

      <p data-testid="script-card-caption" className="text-sm break-words border-t border-border pt-2">
        <span className="text-xs font-medium text-muted-foreground">
          {pickLang(language, SCRIPT_CARD_CAPTION_LABEL)}:{' '}
        </span>
        {script.caption}
      </p>

      <div className="space-y-1">
        <p className="text-xs font-medium text-muted-foreground">
          {pickLang(language, SCRIPT_CARD_BEFORE_YOU_SHOOT_LABEL)}
        </p>
        <ol className="list-decimal space-y-1 pl-5 text-sm">
          {script.beforeYouShoot.map((item, index) => (
            <li key={index} data-testid="script-card-before-item" className="break-words">
              {item}
            </li>
          ))}
        </ol>
      </div>

      <p data-testid="script-card-why" className="text-xs text-muted-foreground break-words">
        {pickLang(language, SCRIPT_CARD_WHY_LABEL)}: {script.whyThisWorks}
      </p>

      {script.followUp ? (
        <p data-testid="script-card-follow-up" className="text-xs text-muted-foreground break-words italic">
          {script.followUp}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2 pt-1">
        <button
          type="button"
          data-testid="script-card-copy"
          // The visible text switches to "Copied"; the accessible name stays "Copy" so a screen
          // reader always hears what the button does. The switch itself is announced below.
          aria-label={pickLang(language, RESULT_CARD_COPY)}
          onClick={handleCopy}
          className="flex h-11 min-w-11 items-center gap-1.5 rounded-lg border border-border bg-muted px-3 text-sm font-medium hover:bg-muted/70 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          {copied ? (
            <>
              <Check className="h-4 w-4 text-primary" aria-hidden="true" />
              {pickLang(language, RESULT_CARD_COPIED)}
            </>
          ) : (
            <>
              <Copy className="h-4 w-4" aria-hidden="true" />
              {pickLang(language, RESULT_CARD_COPY)}
            </>
          )}
        </button>
        <span className="sr-only" aria-live="polite">
          {copied ? pickLang(language, RESULT_CARD_COPIED) : ''}
        </span>
      </div>
    </div>
  );
}

export default MeeraScriptCard;
