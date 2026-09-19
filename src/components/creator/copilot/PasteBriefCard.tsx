import * as React from 'react';
import { FileText, Loader2, MessageCircle } from 'lucide-react';

import { BriefCard } from '@/components/creator/meera/CreatorToolResultRenderer';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Textarea } from '@/components/ui/textarea';
import { api, ApiError, type BriefAnalysisResponse } from '@/lib/api';

/**
 * U-2 (T-MEERA-CREATOR-PHASE-B SPEC.md §8.5) — the paste half of "Paste and Read".
 *
 * `POST /creator/briefs` had no caller anywhere in `src/` before this card, so no creator could
 * paste anything. This is that caller: a textarea, "Analyse with Meera", and the analysis rendered
 * through `BriefCard` (summary lines, extraction chips, the shared risk card, the quote card).
 *
 * Not built here, on purpose: §8.5's "Create secure link" (Phase B1 — `CreatorBriefController`
 * ships no secure-link route). "Open in Meera" is wired as of U-5 (RULINGS-U-0917.md R-U1) — see
 * `onAskMeeraAboutBrief` below; it only FILLS the chat's composer, it never sends on its own.
 *
 * ## Length
 * `PasteBriefRequest.text` is `@Size(max = 8000)`. The cap is enforced in `onChange` rather than
 * with a `maxLength` attribute, because the browser truncates a long paste silently under
 * `maxLength` and the creator would never learn the end of her brief was cut. Here the cut is
 * said out loud. Both sides count UTF-16 code units (`String.length` in JS and Java).
 *
 * ## Errors
 * Inline only, never a toast. `CONSENT_REQUIRED` and `FEATURE_DISABLED` are also handed up so the
 * page can show its consent screen or its calm disabled state.
 */

export const PASTE_BRIEF_MAX_CHARS = 8000;

const countFormat = new Intl.NumberFormat('en-IN');

export interface PasteBriefCardProps {
  /**
   * True only when the page KNOWS consent is missing (its preferences probe said so). The
   * analyse action then asks for consent instead of sending the paste. Unknown (`false`) still
   * sends it — the server refuses with `CONSENT_REQUIRED` before persisting anything.
   */
  needsConsent?: boolean;
  onConsentRequired?: () => void;
  onFeatureDisabled?: () => void;
  /**
   * U-5 — BCP-47, same as `MeeraCopilotChat`'s `language` prop (`CreatorAgentPreferences
   * .creator_language`). Only used to pick the hi-IN wording for the "Ask Meera" button, the same
   * way `ConsentScreen` keys its own text off `language`.
   */
  language?: string;
  /**
   * U-5 — renders "Ask Meera about this brief" once an analysis with a `brief_id` has landed. The
   * button is absent unless BOTH a `brief_id` exists on the result AND this handler is supplied —
   * a page that has not wired up a place to send the creator (a chat entry point) gets no button
   * promising one. The page owns what "asking Meera" means (open/consent-gate the chat); this
   * card only knows there is now a brief id worth asking about.
   */
  onAskMeeraAboutBrief?: (briefId: string) => void;
  className?: string;
}

const CONSENT_MESSAGE =
  'Meera needs your consent before she can read a brief. Accept the notice, then analyse again.';

/** U-5 — wording approved as final by Nisha (NISHA-U4-RECHECK-0917.md §4): it names what Meera
 *  does NOT do (draft or send), and never uses the banned payment-hold word. The hi-IN variant is
 *  keyed off `language`; a Marathi-preference creator currently gets the English copy — Nisha has
 *  flagged that as a product-scope question, not a B0 defect (PRIYA-LASTCALL-U3-U5-0917.md). */
function askMeeraCopy(language: string | undefined) {
  const hi = (language ?? '').startsWith('hi');
  return {
    button: hi ? 'Meera se is brief ke baare mein poochho' : 'Ask Meera about this brief',
    caption: hi
      ? 'Meera ke chat mein yeh brief khul jaayega. Meera kuch likhti ya bhejti nahi — aap decide karte ho.'
      : "Opens Meera's chat with this brief. Meera doesn't draft or send anything — you decide.",
  };
}

export function PasteBriefCard({
  needsConsent = false,
  onConsentRequired,
  onFeatureDisabled,
  language,
  onAskMeeraAboutBrief,
  className,
}: PasteBriefCardProps) {
  const textareaId = React.useId();
  const counterId = React.useId();
  const [text, setText] = React.useState('');
  const [truncated, setTruncated] = React.useState(false);
  const [analysing, setAnalysing] = React.useState(false);
  const [result, setResult] = React.useState<BriefAnalysisResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const mounted = React.useRef(true);
  React.useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Once the page learns consent was given, the "needs your consent" line is no longer true.
  React.useEffect(() => {
    if (!needsConsent) setError((prev) => (prev === CONSENT_MESSAGE ? null : prev));
  }, [needsConsent]);

  const handleChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const next = event.target.value;
    if (next.length > PASTE_BRIEF_MAX_CHARS) {
      setText(next.slice(0, PASTE_BRIEF_MAX_CHARS));
      setTruncated(true);
    } else {
      setText(next);
      setTruncated(false);
    }
  };

  const canAnalyse = text.trim().length > 0 && !analysing;

  const handleAnalyse = async () => {
    if (!canAnalyse) return;
    setError(null);

    if (needsConsent) {
      setError(CONSENT_MESSAGE);
      onConsentRequired?.();
      return;
    }

    setResult(null);
    setAnalysing(true);
    try {
      const analysis = await api.creatorBriefs.paste(text);
      if (!mounted.current) return;
      setResult(analysis);
    } catch (err) {
      if (!mounted.current) return;
      if (err instanceof ApiError && err.code === 'CONSENT_REQUIRED') {
        setError(CONSENT_MESSAGE);
        onConsentRequired?.();
      } else if (err instanceof ApiError && err.code === 'FEATURE_DISABLED') {
        onFeatureDisabled?.();
      } else {
        setError(
          err instanceof ApiError && err.message
            ? err.message
            : "Couldn't analyse this brief. Try again.",
        );
      }
    } finally {
      if (mounted.current) setAnalysing(false);
    }
  };

  return (
    <Card className={className} data-testid="paste-brief-card">
      <CardHeader>
        <div className="flex items-center gap-2">
          <FileText className="h-5 w-5 text-primary" aria-hidden="true" />
          <CardTitle className="text-base">Paste a brand brief</CardTitle>
        </div>
        <CardDescription>
          Paste the brief or the brand&apos;s message. Meera summarises it, flags what to watch and
          suggests a price.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1.5">
          <label htmlFor={textareaId} className="text-sm font-medium">
            Brief text
          </label>
          <Textarea
            id={textareaId}
            value={text}
            onChange={handleChange}
            aria-describedby={counterId}
            placeholder="Paste the brand's brief or message here"
            className="max-h-80 min-h-32"
            disabled={analysing}
          />
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
            <span>
              {truncated
                ? `Only the first ${countFormat.format(PASTE_BRIEF_MAX_CHARS)} characters were kept.`
                : null}
            </span>
            <span id={counterId} data-testid="paste-brief-counter">
              {countFormat.format(text.length)} / {countFormat.format(PASTE_BRIEF_MAX_CHARS)}{' '}
              characters
            </span>
          </div>
        </div>

        <Button type="button" onClick={handleAnalyse} disabled={!canAnalyse}>
          {analysing ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
              Analysing…
            </>
          ) : (
            'Analyse with Meera'
          )}
        </Button>

        {error ? (
          <p
            role="alert"
            data-testid="paste-brief-error"
            className="rounded-lg bg-destructive px-3 py-2 text-sm text-destructive-foreground"
          >
            {error}
          </p>
        ) : null}

        {result ? (
          <BriefCard
            summaryLines={result.summary_lines}
            extraction={result.extraction}
            flags={result.flags}
            quote={result.quote}
            extractionSource={result.extraction_source}
            degradedReason={result.degraded_reason}
            riskScope={result.brief_id ? `BRIEF:${result.brief_id}` : undefined}
          />
        ) : null}

        {/* U-5 — only when BOTH a brief id and a handler exist. The page decides what "asking
            Meera" means (open/consent-gate the chat); this card just knows there is a brief to
            ask about now. */}
        {result?.brief_id && onAskMeeraAboutBrief ? (
          (() => {
            const copy = askMeeraCopy(language);
            return (
              <div className="space-y-1.5 border-t border-border pt-3">
                <Button
                  type="button"
                  variant="outline"
                  data-testid="ask-meera-about-brief"
                  onClick={() => onAskMeeraAboutBrief(result.brief_id)}
                >
                  <MessageCircle className="mr-2 h-4 w-4" aria-hidden="true" />
                  {copy.button}
                </Button>
                <p data-testid="ask-meera-about-brief-caption" className="text-xs text-muted-foreground">
                  {copy.caption}
                </p>
              </div>
            );
          })()
        ) : null}
      </CardContent>
    </Card>
  );
}

export default PasteBriefCard;
