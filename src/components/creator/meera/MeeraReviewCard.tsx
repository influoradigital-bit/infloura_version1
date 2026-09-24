import * as React from 'react';
import { Check, Copy } from 'lucide-react';

import { cn } from '@/lib/utils';
import { copyPlainText } from '@/lib/clipboard';
import {
  RESULT_CARD_COPIED,
  RESULT_CARD_COPY,
  REVIEW_CARD_NEXT_STEPS_LABEL,
  REVIEW_CARD_NOT_WORKING_LABEL,
  REVIEW_CARD_WHAT_FIRST_PROMPT,
  REVIEW_CARD_WHAT_FIRST_LABEL,
  REVIEW_CARD_WORKING_LABEL,
  pickLang,
} from '@/lib/copy/meera-chat';
import type { ParsedMeeraReview } from '@/lib/meera-result-cards';

/**
 * T-MEERA-CREATOR-PHASE-C (PHASE-C-SPEC.md §2) — the profile-review card. Same contract as
 * {@link MeeraScriptCard}: `MeeraCopilotChat.tsx` renders this INSTEAD of the plain bubble only
 * once `parseMeeraReview` has already returned a full, valid object, so nothing here needs to
 * re-check for missing fields.
 */

export interface MeeraReviewCardProps {
  review: ParsedMeeraReview;
  /** The exact, unmodified assistant message text — see `MeeraScriptCard`'s `rawText` doc. */
  rawText: string;
  language: string;
  /** R-U1 — fills the composer, never sends. */
  onPrefill: (text: string) => void;
  className?: string;
}

export function MeeraReviewCard({ review, rawText, language, onPrefill, className }: MeeraReviewCardProps) {
  const [copied, setCopied] = React.useState(false);
  const copiedTimeoutRef = React.useRef<number | null>(null);

  React.useEffect(() => {
    return () => {
      if (copiedTimeoutRef.current !== null) window.clearTimeout(copiedTimeoutRef.current);
    };
  }, []);

  // Synchronous call inside the click handler — see clipboard.ts's doc comment (mobile Safari).
  const handleCopy = () => {
    void copyPlainText(rawText).then((ok) => {
      if (!ok) return;
      setCopied(true);
      if (copiedTimeoutRef.current !== null) window.clearTimeout(copiedTimeoutRef.current);
      copiedTimeoutRef.current = window.setTimeout(() => setCopied(false), 2000);
    });
  };

  const whatFirstPrompt = pickLang(language, REVIEW_CARD_WHAT_FIRST_PROMPT);
  // Short button label; the long sentence above is what gets prefilled into the composer.
  const whatFirstLabel = pickLang(language, REVIEW_CARD_WHAT_FIRST_LABEL);

  return (
    <div
      data-testid="meera-review-card"
      className={cn('space-y-3 rounded-xl border border-border bg-card p-3', className)}
    >
      <div className="space-y-1.5">
        <p data-testid="review-card-working" className="text-sm break-words">
          <span className="text-xs font-medium text-muted-foreground">
            {pickLang(language, REVIEW_CARD_WORKING_LABEL)}:{' '}
          </span>
          <span className="font-medium text-primary">{review.working}</span>
        </p>
        <p data-testid="review-card-not-working" className="text-sm break-words">
          <span className="text-xs font-medium text-muted-foreground">
            {pickLang(language, REVIEW_CARD_NOT_WORKING_LABEL)}:{' '}
          </span>
          {review.notWorking}
        </p>
      </div>

      <div className="space-y-1.5 border-t border-border pt-2">
        <p className="text-xs font-medium text-muted-foreground">
          {pickLang(language, REVIEW_CARD_NEXT_STEPS_LABEL)}
        </p>
        <ol className="list-decimal space-y-1 pl-5 text-sm">
          {review.nextSteps.map((step, index) => (
            <li key={index} data-testid="review-card-next-step" className="break-words">
              {step}
            </li>
          ))}
        </ol>
      </div>

      <div className="flex flex-wrap gap-2 pt-1">
        <button
          type="button"
          data-testid="review-card-copy"
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
          data-testid="review-card-what-first"
          onClick={() => onPrefill(whatFirstPrompt)}
          className="flex h-11 items-center rounded-lg border border-border px-3 text-sm font-medium hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          {whatFirstLabel}
        </button>
      </div>
    </div>
  );
}

export default MeeraReviewCard;
