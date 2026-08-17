/**
 * Brand onboarding wizard — F-0254 (demo-hint-unguarded).
 *
 * `AccountSetupStep`'s email-OTP screen used to print "Demo mode: use code 123456 to verify"
 * unconditionally — no `isApiLive()` guard, on an unguarded onboarding route. A real user on a
 * live build would see a bypass code that does not work against the live server. The fix wraps
 * the hint in `{!isApiLive() && (...)}`, matching the pattern already used in
 * email-otp-gate.tsx and brand-kyc-prompt.tsx.
 *
 * HARNESS NOTES — mirrors src/pages/__tests__/brand-settings.test.tsx's `vi.mock('@/lib/api', ...)`
 * shape: isApiLive as a controllable mock fn, api.* as fine-grained vi.fn()s.
 *
 * Run: npx vitest run src/components/brand/onboarding/__tests__/onboarding-steps.demo-hint.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AccountSetupStep, initialData, type OnboardingData } from '../onboarding-steps';

const apiLive = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => apiLive(),
    api: {
      auth: {
        sendBrandEmailOtp: vi.fn().mockResolvedValue({ sent: true }),
        verifyBrandEmail: vi.fn().mockResolvedValue({ emailVerified: true }),
      },
    },
    ApiError: actual.ApiError,
  };
});

const DEMO_HINT_TEXT = /Demo mode: use code/i;

function renderOtpScreen() {
  // AccountSetupStep's OTP screen renders when emailOtpSent is true and emailOtpVerified is
  // false — this is the branch the demo hint lives in (onboarding-steps.tsx ~line 630).
  const data: OnboardingData = {
    ...initialData,
    email: 'brand@example.com',
    emailOtpSent: true,
    emailOtpVerified: false,
  };
  return render(
    <AccountSetupStep data={data} onUpdate={vi.fn()} onNext={vi.fn()} />,
  );
}

describe('AccountSetupStep OTP screen — demo hint guard (F-0254)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('does NOT render the demo hint when the app is live', () => {
    apiLive.mockReturnValue(true);
    renderOtpScreen();
    expect(screen.queryByText(DEMO_HINT_TEXT)).not.toBeInTheDocument();
  });

  it('DOES render the demo hint when the app is in mock/demo mode', () => {
    apiLive.mockReturnValue(false);
    renderOtpScreen();
    expect(screen.getByText(DEMO_HINT_TEXT)).toBeInTheDocument();
  });
});
