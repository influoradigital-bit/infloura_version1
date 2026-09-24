import * as React from 'react';
import { Minus, TrendingDown, TrendingUp } from 'lucide-react';

import { cn } from '@/lib/utils';
import type { ChallengeComparison } from '@/lib/api';
import type { ChallengeCopy } from '@/lib/copy/creator-challenge';

interface ChallengeComparisonRowProps {
  comparison: ChallengeComparison;
  copy: ChallengeCopy;
  className?: string;
}

/**
 * This-week-vs-last-week comparison (CHALLENGE-SPEC.md Frontend §5): post counts, reach and
 * engagement rate for each week are always shown — they're settled facts about that week, not
 * a claim about a trend. The COMPARISON deltas (`reachChangePercent` / `engagementChangePoints`)
 * only render when `enoughToCompare`; otherwise the server's own `note` explains why, and no
 * percentage or point figure is shown at all (never derived client-side as a fallback).
 *
 * Colour is deliberately asymmetric: up gets the success colour, but down and "same" share the
 * SAME muted treatment — a dip in one week is not a failure (never red), and never described
 * with the word "growing" anywhere in this component.
 *
 * Round 2 QA item 2 — a week whose `reach`/`engagementRate` are drawn from fewer settled posts
 * than it actually had (`settledPosts < posts`) gets its own explanatory line underneath, so a
 * low-looking number doesn't read as a bad week when it's really just posts still settling
 * (Instagram numbers keep moving for ~2 days, CHALLENGE-SPEC.md item 6 backend rule).
 */
export function ChallengeComparisonRow({ comparison, copy, className }: ChallengeComparisonRowProps) {
  const { thisWeek, lastWeek, enoughToCompare, reachChangePercent, note } = comparison;

  return (
    <div className={cn('space-y-2', className)}>
      <div className="grid grid-cols-2 gap-3">
        <div className="rounded-lg bg-muted/50 p-3">
          <p className="text-xs font-medium text-muted-foreground">{copy.comparisonThisWeek}</p>
          <p className="mt-1 text-lg font-semibold tabular-nums">{copy.comparisonPosts(thisWeek.posts)}</p>
          <p className="text-xs text-muted-foreground">
            {thisWeek.reach.toLocaleString('en-IN')} reach · {thisWeek.engagementRate}{' '}
            {copy.comparisonEngagementLabel}
          </p>
          {thisWeek.settledPosts < thisWeek.posts && (
            <p className="mt-1 text-[11px] text-muted-foreground">{copy.settlingNote(thisWeek.settledPosts)}</p>
          )}
        </div>
        <div className="rounded-lg bg-muted/50 p-3">
          <p className="text-xs font-medium text-muted-foreground">{copy.comparisonLastWeek}</p>
          <p className="mt-1 text-lg font-semibold tabular-nums">{copy.comparisonPosts(lastWeek.posts)}</p>
          <p className="text-xs text-muted-foreground">
            {lastWeek.reach.toLocaleString('en-IN')} reach · {lastWeek.engagementRate}{' '}
            {copy.comparisonEngagementLabel}
          </p>
          {lastWeek.settledPosts < lastWeek.posts && (
            <p className="mt-1 text-[11px] text-muted-foreground">{copy.settlingNote(lastWeek.settledPosts)}</p>
          )}
        </div>
      </div>

      {enoughToCompare && reachChangePercent != null ? (
        <div
          className={cn(
            'flex items-center gap-1.5 text-sm font-medium',
            reachChangePercent > 0 ? 'text-success-foreground' : 'text-muted-foreground',
          )}
        >
          {reachChangePercent > 0 && <TrendingUp className="h-4 w-4" aria-hidden="true" />}
          {reachChangePercent < 0 && <TrendingDown className="h-4 w-4" aria-hidden="true" />}
          {reachChangePercent === 0 && <Minus className="h-4 w-4" aria-hidden="true" />}
          <span>
            {reachChangePercent === 0
              ? copy.comparisonSame
              : `${reachChangePercent > 0 ? '+' : ''}${reachChangePercent}% reach`}
          </span>
        </div>
      ) : (
        note && <p className="text-xs text-muted-foreground">{note}</p>
      )}
    </div>
  );
}

export default ChallengeComparisonRow;
