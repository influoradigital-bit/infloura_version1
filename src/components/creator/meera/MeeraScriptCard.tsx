import * as React from 'react';
import { Check, Copy } from 'lucide-react';

import { cn } from '@/lib/utils';
import { copyPlainText } from '@/lib/clipboard';
import {
  RESULT_CARD_COPIED,
  RESULT_CARD_COPY,
  SCRIPT_CARD_ACTION_LABEL,
  SCRIPT_CARD_BEFORE_YOU_SHOOT_LABEL,
  SCRIPT_CARD_CAPTION_LABEL,
  SCRIPT_CARD_ON_SCREEN_LABEL,
  SCRIPT_CARD_PLAN_LABEL,
  SCRIPT_CARD_SUCCESS_LABEL,
  SCRIPT_CARD_WHY_LABEL,
  pickLang,
} from '@/lib/copy/meera-chat';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';

/**
 * The rich reel-script card — reads a `ParsedMeeraScript` (the "Full script format" in
 * `influora-ai/app/prompt/creator_persona.py`, via `parseMeeraScript`) and renders it as a
 * finished card. Rendered by `MeeraCopilotChat.tsx` INSTEAD of the plain bubble once a finished
 * assistant turn's text has parsed; the caller keeps the original bubble as a fallback for an
 * `undefined` parse, so this component can assume `script` is already a fully valid, non-partial
 * shape (`successLooksLike` and `followUp` are the only fields allowed to be absent).
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
}

export function MeeraScriptCard({ script, rawText, language, className }: MeeraScriptCardProps) {
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

  return (
    <div
      data-testid="meera-script-card"
      className={cn('space-y-3 rounded-xl border border-border bg-card p-3', className)}
    >
      <p data-testid="script-card-idea" className="text-sm font-semibold break-words">
        {script.idea}
      </p>

      {/* Plan / Action / Success looks like — short labelled lines, same "muted label + value"
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
            <p className="break-words">
              <span className="text-xs font-medium text-muted-foreground">
                {beat.from}-{beat.to}s ·{' '}
              </span>
              {beat.shot}
            </p>
            <p className="break-words font-medium text-primary [overflow-wrap:anywhere]">&ldquo;{beat.say}&rdquo;</p>
            <p className="break-words text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {onScreenLabel}: {beat.onScreen}
            </p>
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
