import * as React from 'react';
import { Sparkles } from 'lucide-react';

import { cn } from '@/lib/utils';
import type { TrendSparkThemeSource } from '@/lib/api';

export interface ThemeProvenanceBadgeProps {
  /** Provenance of the trend behind the nudge. When `undefined` or `'KEYWORD'`,
   *  nothing renders — the badge only surfaces the interesting case (AI recovery). */
  source?: TrendSparkThemeSource;
  className?: string;
}

/**
 * Trend-Spark trend-tag provenance chip.
 *
 * Surfaces WHEN a trend reached the brand only because the LLM Recovery Tagger
 * (POST /internal/trendspark/tag) rescued it — the deterministic keyword tagger
 * had dropped it. This is transparency, not decoration: it tells the brand (and
 * ops) that "smart AI" is the reason this suggestion exists, so a recovered
 * trend is never silently indistinguishable from a keyword-matched one.
 *
 * Renders NOTHING for the ordinary keyword-matched case (`'KEYWORD'` or absent),
 * keeping the card quiet by default (spec §5b anti-spam ethos) — the chip is a
 * signal, and a signal that's always on is noise.
 *
 * Purely presentational + accessible: an icon + label with a screen-reader
 * description. No motion, no network, no state.
 */
export function ThemeProvenanceBadge({ source, className }: ThemeProvenanceBadgeProps) {
  if (source !== 'AI_RECOVERED') return null;

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border border-meera-border',
        'bg-meera-surface-2 px-2 py-0.5 text-[11px] font-medium text-meera-text-muted',
        className,
      )}
      title="This trend was surfaced by Meera's AI when the keyword tagger missed it."
    >
      <Sparkles className="h-3 w-3 text-meera-accent" aria-hidden="true" />
      <span aria-hidden="true">Spotted by AI</span>
      <span className="sr-only">
        This trend was recovered by AI after the keyword tagger found no match.
      </span>
    </span>
  );
}

export default ThemeProvenanceBadge;
