import { describe, expect, it } from 'vitest';

import { reviewClockLabel, type ReviewClockFields } from '@/lib/review-clock';

const DUE = '2026-09-24T18:29:59.999Z';

function clock(overrides: Partial<ReviewClockFields> = {}): ReviewClockFields {
  return {
    reviewDueAt: DUE,
    reviewWorkingDaysLeft: 3,
    reviewOverdue: false,
    reviewEscalatedAt: null,
    ...overrides,
  };
}

describe('reviewClockLabel', () => {
  it('says nothing when no clock is running', () => {
    expect(reviewClockLabel(null)).toBeNull();
    expect(reviewClockLabel(undefined)).toBeNull();
    // An approved or revision-requested deliverable comes back with the fields nulled out.
    expect(reviewClockLabel(clock({ reviewDueAt: null, reviewWorkingDaysLeft: null }))).toBeNull();
  });

  it('counts down in working days, singular on the last one', () => {
    expect(reviewClockLabel(clock({ reviewWorkingDaysLeft: 3 }))?.brand).toBe('3 working days left to review');
    expect(reviewClockLabel(clock({ reviewWorkingDaysLeft: 2 }))?.brand).toBe('2 working days left to review');
    expect(reviewClockLabel(clock({ reviewWorkingDaysLeft: 1 }))?.brand).toBe('1 working day left to review');
  });

  it('calls zero days left "last day", never "overdue"', () => {
    const label = reviewClockLabel(clock({ reviewWorkingDaysLeft: 0 }));
    expect(label?.brand).toBe('Last day to review');
    expect(label?.tone).toBe('urgent');
    expect(label?.brand).not.toMatch(/overdue/i);
  });

  it('marks it overdue once the deadline has passed', () => {
    const label = reviewClockLabel(clock({ reviewWorkingDaysLeft: 0, reviewOverdue: true }));
    expect(label?.brand).toMatch(/overdue/i);
    expect(label?.tone).toBe('overdue');
  });

  it('says the team has been asked once it has actually escalated', () => {
    const before = reviewClockLabel(clock({ reviewWorkingDaysLeft: 0, reviewOverdue: true }));
    const after = reviewClockLabel(
      clock({ reviewWorkingDaysLeft: 0, reviewOverdue: true, reviewEscalatedAt: '2026-09-25T04:35:00Z' }),
    );
    // "will follow up" before, "has been asked to follow up" after - the copy never claims
    // something happened before it did.
    expect(before?.brand).toContain('will follow up');
    expect(after?.brand).toContain('has been asked to follow up');
    expect(before?.brand).not.toEqual(after?.brand);
  });

  it('never implies an approval or a payment', () => {
    const states: ReviewClockFields[] = [
      clock({ reviewWorkingDaysLeft: 3 }),
      clock({ reviewWorkingDaysLeft: 1 }),
      clock({ reviewWorkingDaysLeft: 0 }),
      clock({ reviewWorkingDaysLeft: 0, reviewOverdue: true }),
      clock({ reviewWorkingDaysLeft: 0, reviewOverdue: true, reviewEscalatedAt: '2026-09-25T04:35:00Z' }),
    ];
    for (const state of states) {
      const label = reviewClockLabel(state);
      const text = `${label?.brand} ${label?.creator}`;
      expect(text).not.toMatch(/auto[- ]?approv/i);
      expect(text).not.toMatch(/automatically approv/i);
      expect(text).not.toMatch(/\bpaid\b|\bpayment\b|\bpayout\b|\brelease[ds]?\b/i);
    }
  });

  it('tells the creator what is happening to their submission, not what they must do', () => {
    expect(reviewClockLabel(clock({ reviewWorkingDaysLeft: 2 }))?.creator).toBe(
      'The brand has 2 working days left to review this.',
    );
    expect(reviewClockLabel(clock({ reviewWorkingDaysLeft: 0, reviewOverdue: true }))?.creator).toContain(
      'Influora team',
    );
  });
});
