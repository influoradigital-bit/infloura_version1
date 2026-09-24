import * as React from 'react';
import { Check, Copy } from 'lucide-react';

import { cn } from '@/lib/utils';
import { copyPlainText } from '@/lib/clipboard';
import {
  RESULT_CARD_COPIED,
  RESULT_CARD_COPY,
  SCRIPT_CARD_ANOTHER_HOOK_PROMPT,
  SCRIPT_CARD_ANOTHER_HOOK_LABEL,
  SCRIPT_CARD_CTA_LABEL,
  SCRIPT_CARD_HOOK_LABEL,
  SCRIPT_CARD_WHY_LABEL,
  pickLang,
} from '@/lib/copy/meera-chat';
import type { ParsedMeeraScript } from '@/lib/meera-result-cards';

/**
 * T-MEERA-CREATOR-PHASE-C (PHASE-C-SPEC.md §2) — the reel-script card. Rendered by
 * `MeeraCopilotChat.tsx` INSTEAD of the plain bubble once a finished assistant turn's text has
 * parsed via `parseMeeraScript`; the caller keeps the original bubble as a fallback for a `null`
 * parse, so this component can assume `script` is already a fully valid, non-partial shape.
 *
 * Creator tokens only (`border-border`, `bg-card`, `bg-muted`, `text-primary`) — same rule
 * `CreatorToolResultRenderer.tsx` documents for the other creator-side cards. No `--meera-stage`
 * here: that dark ink is the chat HEADER's colour (`MeeraCopilotChat.tsx`), not reused elsewhere.
 */

export interface MeeraScriptCardProps {
  script: ParsedMeeraScript;
  /** The exact, unmodified assistant message text — what Copy writes and what "Show as text"
   *  reveals. Never reconstructed from `script`, so Copy is always byte-for-byte what Meera
   *  actually sent, including any language-specific phrasing`parseMeeraScript` does not retain. */
  rawText: string;
  language: string;
  /** R-U1 — fills the composer, never sends. Same contract as `MeeraDesk`'s `onPrefill`. */
  onPrefill: (text: string) => void;
  className?: string;
}

export function MeeraScriptCard({ script, rawText, language, onPrefill, className }: MeeraScriptCardProps) {
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

  const anotherHookPrompt = pickLang(language, SCRIPT_CARD_ANOTHER_HOOK_PROMPT);
  // Short button label; the long sentence above is what gets prefilled into the composer.
  const anotherHookLabel = pickLang(language, SCRIPT_CARD_ANOTHER_HOOK_LABEL);

  return (
    <div
      data-testid="meera-script-card"
      className={cn('space-y-3 rounded-xl border border-border bg-card p-3', className)}
    >
      <div className="flex items-start justify-between gap-2">
        <p data-testid="script-card-title" className="text-sm font-semibold break-words">
          {script.title}
        </p>
        <span
          data-testid="script-card-length"
          className="shrink-0 rounded-md bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground"
        >
          {script.length}s
        </span>
      </div>

      <p data-testid="script-card-hook" className="text-sm break-words">
        <span className="text-xs font-medium text-muted-foreground">
          {pickLang(language, SCRIPT_CARD_HOOK_LABEL)}:{' '}
        </span>
        <span className="font-medium text-primary">{script.hook}</span>
      </p>

      {/* Two-column beat list — `0-10s` left, text right. Stacks to one column under 380px, the
          spec's phone-first breakpoint, so a long shot description never gets squeezed into a
          narrow right column on the smallest phones. */}
      <ul className="space-y-2">
        {script.beats.map((beat, index) => (
          <li
            key={`${beat.from}-${beat.to}-${index}`}
            data-testid="script-card-beat"
            className="grid grid-cols-1 gap-1 text-sm min-[380px]:grid-cols-[4.5rem_1fr] min-[380px]:gap-3"
          >
            <span className="text-xs font-medium text-muted-foreground">
              {beat.from}-{beat.to}s
            </span>
            <span className="break-words">{beat.text}</span>
          </li>
        ))}
      </ul>

      <p data-testid="script-card-cta" className="text-sm break-words">
        <span className="text-xs font-medium text-muted-foreground">
          {pickLang(language, SCRIPT_CARD_CTA_LABEL)}:{' '}
        </span>
        {script.cta}
      </p>

      {script.why ? (
        <p data-testid="script-card-why" className="text-xs text-muted-foreground break-words">
          {pickLang(language, SCRIPT_CARD_WHY_LABEL)}: {script.why}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2 pt-1">
        <button
          type="button"
          data-testid="script-card-copy"
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

        <button
          type="button"
          data-testid="script-card-another-hook"
          onClick={() => onPrefill(anotherHookPrompt)}
          className="flex h-11 items-center rounded-lg border border-border px-3 text-sm font-medium hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          {anotherHookLabel}
        </button>
      </div>
    </div>
  );
}

export default MeeraScriptCard;
