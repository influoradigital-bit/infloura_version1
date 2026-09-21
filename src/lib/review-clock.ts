/**
 * How the brand's review clock is worded, in one place.
 *
 * The rule (owner's ruling, 2026-09-21): a brand has 3 working days to approve, reject or ask for
 * a revision on a submitted draft, and 2 working days on each resubmission. If it does none of
 * those, the deliverable goes to the Influora team, who chase it as people. Nothing is ever
 * auto-approved and nothing is ever auto-paid — so no label here may imply otherwise.
 *
 * The numbers come from the server (`ReviewSlaService.clockFor`), which counts the working days
 * and owns the deadline. This module only decides what to call them, so the brand's screen, the
 * creator's screen and anywhere else the clock appears read the same way.
 */

/** The clock fields as they arrive on a deliverable DTO. */
export interface ReviewClockFields {
  reviewDueAt: string | null;
  reviewWorkingDaysLeft: number | null;
  reviewOverdue: boolean;
  reviewEscalatedAt: string | null;
}

export type ReviewClockTone = 'normal' | 'urgent' | 'overdue';

export interface ReviewClockLabel {
  /** What the brand is told — always about what THEY still owe. */
  brand: string;
  /** What the creator is told — always about what is happening to THEIR submission. */
  creator: string;
  tone: ReviewClockTone;
}

/**
 * Null when no clock is running — nothing is waiting on the brand, so there is nothing to say.
 * Callers render nothing rather than an empty or zeroed countdown.
 */
export function reviewClockLabel(clock: ReviewClockFields | null | undefined): ReviewClockLabel | null {
  if (!clock || clock.reviewDueAt == null || clock.reviewWorkingDaysLeft == null) {
    return null;
  }

  if (clock.reviewEscalatedAt != null) {
    return {
      // Not phrased as a punishment, and not as a threat about money: the brand's decision is
      // still the brand's to make. It only says what has actually happened.
      brand: 'Review overdue — the Influora team has been asked to follow up',
      creator: 'Your draft is overdue for review. The Influora team is chasing it.',
      tone: 'overdue',
    };
  }

  if (clock.reviewOverdue) {
    return {
      brand: 'Review overdue — the Influora team will follow up',
      creator: 'Your draft is overdue for review. The Influora team will follow up.',
      tone: 'overdue',
    };
  }

  const daysLeft = clock.reviewWorkingDaysLeft;

  if (daysLeft <= 0) {
    // 0 means the deadline is the END of today, not that time has run out.
    return {
      brand: 'Last day to review',
      creator: 'Today is the last day the brand has to review this.',
      tone: 'urgent',
    };
  }

  const dayWord = daysLeft === 1 ? 'working day' : 'working days';
  return {
    brand: `${daysLeft} ${dayWord} left to review`,
    creator: `The brand has ${daysLeft} ${dayWord} left to review this.`,
    tone: daysLeft === 1 ? 'urgent' : 'normal',
  };
}
