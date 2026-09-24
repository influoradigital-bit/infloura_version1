import * as React from 'react';
import { Check, Loader2, Minus } from 'lucide-react';

import { cn } from '@/lib/utils';
import type { ChallengeDay } from '@/lib/api';
import type { ChallengeCopy } from '@/lib/copy/creator-challenge';

interface ChallengeDayStripProps {
  days: ChallengeDay[];
  copy: ChallengeCopy;
  className?: string;
}

/** `MediaMetric.mediaType` (wire value the day was ticked off by) -> the same short label
 *  used for `plannedType`, so a mismatched day reads as "Posted Carousel instead" rather
 *  than a raw enum string (CHALLENGE-SPEC.md "Facts already verified": Instagram reports a
 *  reel as VIDEO, so VIDEO/REELS both mean REEL; CAROUSEL_ALBUM means CAROUSEL; IMAGE means
 *  POST). Unmapped/unknown values fall back to the raw string rather than throwing. */
function postedTypeLabel(postedType: string | null, copy: ChallengeCopy): string {
  if (!postedType) return '';
  if (postedType === 'VIDEO' || postedType === 'REELS') return copy.plannedTypeLabel.REEL;
  if (postedType === 'CAROUSEL_ALBUM') return copy.plannedTypeLabel.CAROUSEL;
  if (postedType === 'IMAGE') return copy.plannedTypeLabel.POST;
  return postedType;
}

/**
 * The 7-day strip (CHALLENGE-SPEC.md Frontend §6; redesigned per Round 2 QA item 4 — a bare
 * letter-in-a-circle with the status word underneath read as cryptic in a real browser).
 *
 * Every cell now shows two PLAIN, always-visible facts — the weekday and the post type
 * ("Reel"/"Carousel"/"Post"/"Rest") — and conveys status through styling only: DONE is a
 * green check, TODAY is a purple ring, UPCOMING is muted, MISSED is a muted dash (never red —
 * a missed day is the same visual register as a rest day, not a failure state), and CHECKING
 * is the one status that still needs its own word ("checking…", there is no icon for "we
 * don't know yet"). A DONE day with `matchedType: false` swaps its caption for what was
 * actually posted, in the same honest, non-judgmental tone.
 *
 * Fits 7 columns at 375px with no sideways scroll: the grid itself is `w-full`/`grid-cols-7`
 * (no fixed pixel widths), and the longest caption ("Carousel") wraps within its own column
 * (`break-words`) instead of forcing the column wider.
 */
export function ChallengeDayStrip({ days, copy, className }: ChallengeDayStripProps) {
  return (
    <div className={cn('grid w-full grid-cols-7 gap-1', className)} role="list">
      {days.map((day) => {
        const caption =
          day.status === 'CHECKING'
            ? copy.stripStatusLabel.CHECKING
            : day.status === 'DONE' && day.matchedType === false
              ? copy.stripPostedInstead(postedTypeLabel(day.postedType, copy))
              : copy.plannedTypeLabel[day.plannedType];

        return (
          <div key={day.dayIndex} role="listitem" className="flex min-w-0 flex-col items-center gap-0.5">
            <span className="text-[11px] font-medium text-muted-foreground">{copy.weekdayShort(day.date)}</span>
            <div
              className={cn(
                'flex h-8 w-8 shrink-0 items-center justify-center rounded-full',
                day.status === 'DONE' && 'bg-success text-success-foreground',
                day.status === 'TODAY' && 'border-2 border-primary',
                (day.status === 'UPCOMING' || day.status === 'CHECKING') && 'bg-muted text-muted-foreground',
                // Never red — same muted register as a rest/upcoming day, not a failure state.
                day.status === 'MISSED' && 'bg-muted text-muted-foreground',
                day.status === 'REST' && 'bg-muted/60 text-muted-foreground',
              )}
              title={`${copy.stripStatusLabel[day.status]} — ${day.date}`}
              aria-label={`${copy.stripStatusLabel[day.status]}, ${day.date}`}
            >
              {day.status === 'DONE' && <Check className="h-4 w-4" aria-hidden="true" />}
              {day.status === 'CHECKING' && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />}
              {day.status === 'MISSED' && <Minus className="h-4 w-4" aria-hidden="true" />}
            </div>
            <span className="w-full break-words text-center text-[11px] leading-tight text-muted-foreground">
              {caption}
            </span>
          </div>
        );
      })}
    </div>
  );
}

export default ChallengeDayStrip;
