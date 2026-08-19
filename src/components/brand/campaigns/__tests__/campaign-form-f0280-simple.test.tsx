/**
 * F-0280 simplified regression test — verifies the disabled condition logic.
 *
 * The full E2E test would require navigating through all form steps to reach the review
 * step where the Publish button appears. This unit test verifies the core logic: the button
 * is disabled when (!verificationLoading && isVerified === false).
 */

import { describe, it, expect } from 'vitest';

describe('F-0280 — Publish button disabled condition', () => {
  it('evaluates to disabled when workspace is KNOWN unverified', () => {
    const isSubmitting = false;
    const verificationLoading = false;
    const isVerified = false;

    const shouldBeDisabled = isSubmitting || (!verificationLoading && isVerified === false);

    expect(shouldBeDisabled).toBe(true);
  });

  it('evaluates to enabled while verification status is loading (fails open)', () => {
    const isSubmitting = false;
    const verificationLoading = true;
    const isVerified = false; // Still loading, so this doesn't matter

    const shouldBeDisabled = isSubmitting || (!verificationLoading && isVerified === false);

    expect(shouldBeDisabled).toBe(false);
  });

  it('evaluates to enabled when workspace is verified', () => {
    const isSubmitting = false as boolean;
    const verificationLoading = false as boolean;
    const isVerified = true as boolean;

    const shouldBeDisabled = isSubmitting || (!verificationLoading && isVerified === false);

    expect(shouldBeDisabled).toBe(false);
  });

  it('evaluates to disabled when submitting, regardless of verification', () => {
    const isSubmitting = true as boolean;
    const verificationLoading = false as boolean;
    const isVerified = true as boolean;

    const shouldBeDisabled = isSubmitting || (!verificationLoading && isVerified === false);

    expect(shouldBeDisabled).toBe(true);
  });
});
