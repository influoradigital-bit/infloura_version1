/**
 * F-0280 — the Publish button must be disabled only when the workspace is KNOWN unverified.
 *
 * WHY THIS FILE WAS REWRITTEN (F-0675)
 * -------------------------------------
 * The previous version of this test imported NOTHING from the codebase. It re-declared the
 * disabled rule as local constants:
 *
 *     const shouldBeDisabled = isSubmitting || (!verificationLoading && isVerified === false);
 *     expect(shouldBeDisabled).toBe(true);
 *
 * and asserted on its own copy. That is a tautology dressed as a regression test: change the real
 * condition in `campaign-form.tsx` and this file stays green forever, because it never reads it.
 * Its own docstring conceded it avoided rendering the component. A gate written for exactly this
 * class (F-0663, "a test filed under an F-id that reaches its subject through no import path at
 * all") found it on its first clean run.
 *
 * The fix was to EXTRACT the rule into `isPublishDisabled` in campaign-form.tsx and have both the
 * component and this test evaluate the same function — the remedy already used here for
 * `buildCounterOfferBody` (F-0432). Now editing the rule at the button moves these assertions.
 *
 * Run: npx vitest run src/components/brand/campaigns/__tests__/campaign-form-f0280-simple.test.tsx
 */

import { describe, it, expect } from 'vitest';

// The REAL production predicate. If this import ever breaks, or the function is inlined back into
// the button, that is the defect this file exists to catch — not an inconvenience to route around.
import { isPublishDisabled } from '../campaign-form';

describe('F-0280 — Publish button disabled rule (the real one)', () => {
  it('disables when the workspace is KNOWN unverified', () => {
    expect(
      isPublishDisabled({ isSubmitting: false, verificationLoading: false, isVerified: false }),
    ).toBe(true);
  });

  it('stays ENABLED while verification is still loading — fails open, does not block the user', () => {
    // The load-bearing case. `isVerified` is false here too, but nothing is known yet: collapsing
    // this to a falsy check would block every brand whose status has not resolved.
    expect(
      isPublishDisabled({ isSubmitting: false, verificationLoading: true, isVerified: false }),
    ).toBe(false);
  });

  it.each([
    ['null', null],
    ['undefined', undefined],
  ])('stays ENABLED when verification status is %s (unknown, not negative)', (_label, value) => {
    expect(
      isPublishDisabled({
        isSubmitting: false,
        verificationLoading: false,
        isVerified: value as boolean | null | undefined,
      }),
    ).toBe(false);
  });

  it('enables for a verified workspace', () => {
    expect(
      isPublishDisabled({ isSubmitting: false, verificationLoading: false, isVerified: true }),
    ).toBe(false);
  });

  it('disables while submitting, regardless of verification', () => {
    expect(
      isPublishDisabled({ isSubmitting: true, verificationLoading: false, isVerified: true }),
    ).toBe(true);
  });

  it('is the SAME function the Publish button uses — not a copy of its logic', async () => {
    // Guards the regression this rewrite exists to prevent: a future edit inlining the condition
    // back into the JSX would leave the assertions above passing against an orphaned helper.
    const fs = await import('node:fs');
    // Plain cwd-relative path: `import.meta.url` is not a file: URL under this vitest config
    // (the first cut of this assertion died with "The URL must be of scheme file").
    const src = fs.readFileSync(
      'src/components/brand/campaigns/campaign-form.tsx',
      'utf8',
    );
    expect(
      src.includes('disabled={isPublishDisabled('),
      'the Publish button no longer calls isPublishDisabled — this suite would keep passing while ' +
        'the real button used a different rule, which is exactly how F-0280 shipped untested',
    ).toBe(true);
  });
});
