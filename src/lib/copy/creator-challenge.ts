/**
 * Bilingual (en / hi) copy for the creator 7-day challenge (CHALLENGE-SPEC.md, 2026-09-23,
 * Frontend §8; Round 2 QA fixes item 3). Hindi is DEVANAGARI, matching
 * `src/components/meera/ConsentScreen.tsx` and `src/components/creator/MeeraSettingsSection.tsx`
 * — this card sits on the same Co-pilot page as Meera, whose Hindi is Devanagari, not
 * Romanized. Same mixed register as `ConsentScreen.tsx` ("Meera आपकी AI मैनेजर है"): common
 * tech/product loanwords are transliterated into Devanagari script (चैलेंज, स्ट्रीक, कनेक्ट),
 * while the four content-type nouns creators actually use in English stay in Latin script
 * untranslated — `plannedTypeLabel` is deliberately identical in both languages.
 *
 * Honesty rules baked into these strings, not left to call sites to remember
 * (CHALLENGE-SPEC.md "Facts already verified" + Frontend §4/§5):
 *   - never the word "growing"
 *   - `suggestedBadge` is the ONLY place "suggested" is said, and no copy here ever says
 *     "your best time" either — both windows get a neutral time label; only the suggested
 *     one gets an extra badge, so nothing here is a promise the data doesn't back.
 *   - a MISSED day and a down week both read as neutral, not as failure — colour/icon choice
 *     (never red) is the component's job, but the words themselves stay neutral too.
 */

import type { ChallengeDayStatus, ChallengePlannedType } from '@/lib/api';
import { weekdayIndex } from '@/lib/creator-challenge-format';

export interface ChallengeCopy {
  notConnectedHeading: string;
  notConnectedBody: string;
  introHeading: string;
  introBody: string;
  startCta: string;
  startingCta: string;
  todayHeading: string;
  suggestedBadge: string;
  writeScript: string;
  giveIdea: string;
  streakLabel: (n: number) => string;
  /** Compact "Day N of 7" — used where space is tight (the dashboard tile). */
  dayOfLabel: (day: number) => string;
  /** The full card heading (Round 2 QA item 7) — "Day 3 of 7" alone had no context;
   *  this names what it's day 3 OF. */
  challengeHeading: (day: number) => string;
  restDayTitle: string;
  restDayBody: string;
  comparisonThisWeek: string;
  comparisonLastWeek: string;
  comparisonPosts: (n: number) => string;
  comparisonSame: string;
  /** Round 2 QA item 2 — shown under a week's stats only when `settledPosts < posts` for
   *  that week: explains why the numbers above look low without implying anything is wrong. */
  settlingNote: (settledPosts: number) => string;
  completedHeadline: (daysDone: number, daysPlanned: number) => string;
  startNextCta: string;
  /** The quiet text button at the bottom of the active card (Round 2 QA item 1). */
  endChallenge: string;
  endingChallenge: string;
  /** Confirm-dialog copy for ending early. */
  endConfirmTitle: string;
  endConfirmBody: string;
  keepGoingCta: string;
  endItCta: string;
  errorAlreadyActive: string;
  errorNotConnected: string;
  errorGeneric: string;
  plannedTypeLabel: Record<ChallengePlannedType, string>;
  stripStatusLabel: Record<ChallengeDayStatus, string>;
  stripPostedInstead: (typeLabel: string) => string;
  /** `window.label` ("morning" | "afternoon" | "evening" | "night") -> a natural phrase
   *  ("this morning" / "tonight"). Unknown labels fall back to the raw string so a future
   *  daypart the backend adds degrades to plain text instead of `undefined`. */
  daypartPhrase: (label: string) => string;
  /** A `ChallengeDay.date` ("2026-09-23") -> a short weekday label for the strip (Round 2 QA
   *  item 4), en/hi. */
  weekdayShort: (isoDate: string) => string;
}

const WEEKDAY_SHORT_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
/** Standard short Hindi weekday names (Devanagari), not transliterations of the English
 *  ones — this is how these are actually written in Hindi. */
const WEEKDAY_SHORT_HI = ['रवि', 'सोम', 'मंगल', 'बुध', 'गुरु', 'शुक्र', 'शनि'];

export function challengeCopy(language?: string): ChallengeCopy {
  const hi = (language ?? '').startsWith('hi');

  // Deliberately identical in both languages — Reel/Carousel/Post/Rest are the words
  // creators actually use, in either language (Round 2 QA item 3 instruction).
  const plannedTypeLabel: Record<ChallengePlannedType, string> = {
    REEL: 'Reel',
    CAROUSEL: 'Carousel',
    POST: 'Post',
    REST: 'Rest',
  };

  const stripStatusLabel: Record<ChallengeDayStatus, string> = hi
    ? {
        DONE: 'हो गया',
        TODAY: 'आज',
        UPCOMING: 'आने वाला',
        CHECKING: 'चेक कर रहे हैं…',
        MISSED: 'मिस हो गया',
        REST: 'Rest',
      }
    : {
        DONE: 'Done',
        TODAY: 'Today',
        UPCOMING: 'Upcoming',
        CHECKING: 'Checking…',
        MISSED: 'Missed',
        REST: 'Rest',
      };

  return {
    notConnectedHeading: hi
      ? 'चैलेंज शुरू करने के लिए Instagram कनेक्ट करें'
      : 'Connect Instagram to start the challenge',
    notConnectedBody: hi
      ? 'यह चैलेंज आपकी अपनी Instagram posts से बनता है, इसलिए पहले उसे कनेक्ट करना होगा।'
      : "The challenge is built from your own Instagram posts, so it needs to be connected first.",
    introHeading: hi ? '7 दिन, एक प्लान, एक स्ट्रीक' : 'A 7-day plan, built from your own posts',
    introBody: hi
      ? 'हर दिन के लिए एक post type और एक समय मिलेगा, आपके अपने posting pattern से — साथ में एक rest day भी।'
      : 'Every day gets a post type and a window to aim for, based on your own posting pattern — with one rest day built in.',
    startCta: hi ? 'मेरा 7-दिन चैलेंज शुरू करें' : 'Start my 7-day challenge',
    startingCta: hi ? 'शुरू हो रहा है…' : 'Starting…',
    todayHeading: hi ? 'आज का काम' : "Today's task",
    suggestedBadge: hi ? 'Suggested' : 'Suggested',
    writeScript: hi ? 'Script लिखें' : 'Write the script',
    giveIdea: hi ? 'एक idea दें' : 'Give me an idea',
    streakLabel: (n: number) => (hi ? `${n} दिन की स्ट्रीक` : `${n} day streak`),
    dayOfLabel: (day: number) => (hi ? `दिन ${day} / 7` : `Day ${day} of 7`),
    challengeHeading: (day: number) =>
      hi ? `आपका 7-दिन चैलेंज · दिन ${day} / 7` : `Your 7-day challenge · Day ${day} of 7`,
    restDayTitle: hi ? 'आज Rest day है' : "Today's a rest day",
    restDayBody: hi
      ? 'आज के लिए कोई post प्लान नहीं है। कल फिर से शुरू।'
      : 'No post is planned for today. Back to it tomorrow.',
    comparisonThisWeek: hi ? 'इस हफ़्ते' : 'This week',
    comparisonLastWeek: hi ? 'पिछले हफ़्ते' : 'Last week',
    comparisonPosts: (n: number) => `${n} post${n === 1 ? '' : 's'}`,
    comparisonSame: hi ? 'बराबर' : 'Same',
    settlingNote: (settledPosts: number) =>
      hi
        ? `Reach और engagement सिर्फ़ ${settledPosts} settled post${settledPosts === 1 ? '' : 's'} से हैं — नई posts अभी भी views जोड़ रही हैं।`
        : `Reach and engagement from ${settledPosts} settled post${settledPosts === 1 ? '' : 's'} — newer posts are still collecting views.`,
    completedHeadline: (daysDone: number, daysPlanned: number) =>
      hi
        ? `आपने ${daysPlanned} में से ${daysDone} दिन post किया`
        : `You posted on ${daysDone} of ${daysPlanned} days`,
    startNextCta: hi ? 'अगले 7 दिन शुरू करें' : 'Start the next 7 days',
    endChallenge: hi ? 'चैलेंज खत्म करें' : 'End challenge',
    endingChallenge: hi ? 'खत्म हो रहा है…' : 'Ending…',
    endConfirmTitle: hi ? 'यह चैलेंज खत्म करें?' : 'End this challenge?',
    endConfirmBody: hi
      ? 'आपने जितने दिन पूरे किए हैं वो गिने ही रहेंगे।'
      : "The days you've done stay counted.",
    keepGoingCta: hi ? 'जारी रखें' : 'Keep going',
    endItCta: hi ? 'खत्म करें' : 'End it',
    errorAlreadyActive: hi ? 'एक चैलेंज पहले से active है।' : 'A challenge is already active.',
    errorNotConnected: hi ? 'पहले Instagram कनेक्ट करें।' : 'Connect Instagram first.',
    errorGeneric: hi ? 'कुछ गड़बड़ हो गई। दोबारा कोशिश करें।' : 'Something went wrong. Please try again.',
    plannedTypeLabel,
    stripStatusLabel,
    stripPostedInstead: (typeLabel: string) => (hi ? `इसकी जगह ${typeLabel} post हुआ` : `Posted ${typeLabel} instead`),
    daypartPhrase: (label: string) => {
      const en: Record<string, string> = {
        morning: 'this morning',
        afternoon: 'this afternoon',
        evening: 'this evening',
        night: 'tonight',
      };
      const hiMap: Record<string, string> = {
        morning: 'आज सुबह',
        afternoon: 'आज दोपहर',
        evening: 'आज शाम',
        night: 'आज रात',
      };
      const map = hi ? hiMap : en;
      return map[label] ?? label;
    },
    weekdayShort: (isoDate: string) => (hi ? WEEKDAY_SHORT_HI : WEEKDAY_SHORT_EN)[weekdayIndex(isoDate)] ?? '',
  };
}

export default challengeCopy;
