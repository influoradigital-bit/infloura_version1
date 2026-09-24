/**
 * Pure, language-independent formatting helpers for the creator 7-day challenge
 * (CHALLENGE-SPEC.md, 2026-09-23). No copy/strings live here — bilingual labels are
 * `src/lib/copy/creator-challenge.ts`'s job; this file only turns wire values (24h
 * "HH:MM" strings, a `ChallengeWindow`) into numbers/ranges a copy function can drop
 * into a sentence.
 */

import type { ChallengeWindow } from '@/lib/api';

/** "17:00" -> { hour12: 5, period: 'pm' }. Minutes are dropped — every window boundary
 *  in this feature falls on the hour (dayparts are whole-hour buckets), so there is
 *  nothing to lose; a non-hour value still degrades safely to the nearest hour label. */
function to12Hour(hhmm: string): { hour12: number; period: 'am' | 'pm' } {
  const [hStr] = hhmm.split(':');
  const h = Number.parseInt(hStr, 10) || 0;
  const period: 'am' | 'pm' = h >= 12 ? 'pm' : 'am';
  let hour12 = h % 12;
  if (hour12 === 0) hour12 = 12;
  return { hour12, period };
}

/** "17:00"/"22:00" -> "5–10 pm"; a range that crosses noon/midnight gets both suffixes,
 *  e.g. "10 am–2 pm". Never throws on a malformed string — falls back to the raw pair. */
export function formatWindowTimeRange(from: string, to: string): string {
  if (!from || !to) return '';
  try {
    const a = to12Hour(from);
    const b = to12Hour(to);
    if (a.period === b.period) {
      return `${a.hour12}–${b.hour12} ${b.period}`;
    }
    return `${a.hour12} ${a.period}–${b.hour12} ${b.period}`;
  } catch {
    return `${from}–${to}`;
  }
}

/** Full "5–10 pm" style range for a `ChallengeWindow`, or '' when there is none
 *  (REST day). */
export function formatWindow(window: ChallengeWindow | null): string {
  if (!window) return '';
  return formatWindowTimeRange(window.from, window.to);
}

/** `"2026-09-23"` -> `0`–`6` (Sun–Sat), same numbering as `Date#getDay`. Parsed as a plain
 *  calendar date (`new Date(y, m-1, d)`, local time) rather than `new Date(isoDate)` (UTC
 *  midnight) — the latter shifts a day backward in every timezone west of UTC, which would
 *  show the wrong weekday on the strip for every creator not on/east of UTC. Malformed input
 *  degrades to `0` rather than throwing. */
export function weekdayIndex(isoDate: string): number {
  const [y, m, d] = isoDate.split('-').map((v) => Number.parseInt(v, 10));
  if (!y || !m || !d) return 0;
  return new Date(y, m - 1, d).getDay();
}
