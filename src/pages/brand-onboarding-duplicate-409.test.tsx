/**
 * Brand Onboarding — duplicate phone/email 409s get field-adjacent handling on step 1
 * (PHONE-0904 Q6 fix).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * `api.auth.brandRegister` is called from step 2's "Continue" (`handleCompanySaveAndNext` in
 * brand-onboarding.tsx), but the mobile-number and email fields it can 409 on both live on
 * step 1 (onboarding-steps.tsx's AccountSetupStep). Before this fix, EVERY register failure —
 * PHONE_ALREADY_EXISTS, EMAIL_ALREADY_EXISTS, or anything else — fell into one page-level
 * banner rendered on step 2, nowhere near the field it was about, and the two duplicate
 * reasons were textually indistinguishable from each other (Priya's PHONE-0904 Q6
 * sign-off finding, NEGATIVE/blocking).
 *
 * The fix branches on `err.code` (AuthService.java:123 EMAIL_ALREADY_EXISTS,
 * UserPhoneService.java:113/:123 PHONE_ALREADY_EXISTS via AuthService.java:139-143/:195-198),
 * routes the user back to step 1, and shows the message next to the field that caused it.
 *
 * HARNESS NOTES — mirrors onboarding-steps.persistence.test.tsx's `vi.mock('@/lib/api', ...)`
 * shape and its ResizeObserver/pointer-capture reliance on src/test/setup.ts for the Radix
 * Selects on step 2.
 *
 * Run: npx vitest run src/pages/brand-onboarding-duplicate-409.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BrandOnboardingPage from './brand-onboarding';
import { ApiError } from '@/lib/api';

const sendBrandEmailOtp = vi.fn();
const verifyBrandEmail = vi.fn();
const brandRegister = vi.fn();
const saveBrandCompany = vi.fn();
const checkSlug = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false,
    api: {
      auth: {
        sendBrandEmailOtp: (...a: unknown[]) => sendBrandEmailOtp(...a),
        verifyBrandEmail: (...a: unknown[]) => verifyBrandEmail(...a),
        brandRegister: (...a: unknown[]) => brandRegister(...a),
      },
      onboarding: {
        saveBrandCompany: (...a: unknown[]) => saveBrandCompany(...a),
        completeBrand: vi.fn(),
      },
      workspaces: { checkSlug: (...a: unknown[]) => checkSlug(...a) },
    },
  };
});

vi.mock('@/hooks/use-toast', () => ({ toast: vi.fn() }));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/onboarding']}>
        <BrandOnboardingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Fills and submits step 1 (Account Setup + email OTP), landing on step 2. */
async function completeStep1(
  user: ReturnType<typeof userEvent.setup>,
  overrides: { email?: string; phone?: string } = {},
) {
  await user.type(screen.getByLabelText(/First name/i), 'Rahul');
  await user.type(screen.getByLabelText(/Last name/i), 'Sharma');
  await user.type(screen.getByLabelText(/Work email/i), overrides.email ?? 'rahul@acme.com');
  await user.type(screen.getByLabelText(/Mobile number/i), overrides.phone ?? '9876543210');
  await user.type(screen.getByLabelText('Password'), 'hunter2hunter2');
  await user.type(screen.getByLabelText(/Confirm password/i), 'hunter2hunter2');
  await user.click(screen.getByRole('checkbox'));
  await user.click(screen.getByRole('button', { name: /Send verification code/i }));

  await screen.findByText(/Verify your email/i);
  const otpBoxes = document.querySelectorAll('input[inputmode="numeric"]');
  await user.click(otpBoxes[0]);
  await user.paste('123456');
  await user.click(screen.getByRole('button', { name: /Verify email/i }));

  // handleVerifyOtp advances to step 2 via a 600ms setTimeout after 'Email verified' flashes.
  await screen.findByText(/Tell us about your brand/i, undefined, { timeout: 3000 });
}

/** Fills step 2's required fields and submits, exercising brandRegister + saveBrandCompany. */
async function submitStep2(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/Company name/i), 'Acme Co');

  const comboboxes = screen.getAllByRole('combobox');
  await user.click(comboboxes[0]); // Industry
  await user.click(await screen.findByRole('option', { name: 'Technology' }));
  await user.click(comboboxes[1]); // Company size
  await user.click(await screen.findByRole('option', { name: /Startup/i }));

  await user.click(screen.getByRole('button', { name: /Continue/i }));
}

describe('BrandOnboardingPage — duplicate phone/email 409 handling (PHONE-0904 Q6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sendBrandEmailOtp.mockResolvedValue({ sent: true });
    verifyBrandEmail.mockResolvedValue({ emailVerified: true });
    saveBrandCompany.mockResolvedValue({});
    checkSlug.mockResolvedValue({ slug: 'acme-co', available: true, suggestions: [] });
  });

  it('a duplicate-phone 409 lands the user back on step 1 with a field-adjacent message, and does not advance', async () => {
    brandRegister.mockRejectedValue(
      new ApiError('PHONE_ALREADY_EXISTS', 'An account with this phone number already exists', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();

    await completeStep1(user);
    await submitStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    // Back on step 1 — the mobile field is visible again, step 2's company field is not.
    expect(await screen.findByLabelText(/Mobile number/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Company name/i)).not.toBeInTheDocument();

    // Field-adjacent, not a page-level banner: the message sits next to the phone input.
    const phoneError = await screen.findByText(/This mobile number is already registered/i);
    expect(phoneError).toBeInTheDocument();
    expect(screen.getByLabelText(/Mobile number/i).closest('div')?.parentElement).toContainElement(
      phoneError,
    );

    // saveBrandCompany must never have been reached — registration itself failed.
    expect(saveBrandCompany).not.toHaveBeenCalled();
  });

  it('a duplicate-email 409 is distinguishable from the phone message and is also field-adjacent on step 1', async () => {
    brandRegister.mockRejectedValue(
      new ApiError('EMAIL_ALREADY_EXISTS', 'An account with this email already exists', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();

    await completeStep1(user);
    await submitStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    expect(await screen.findByLabelText(/Work email/i)).toBeInTheDocument();
    // Distinguishable — the phone-specific copy must NOT appear for an email conflict.
    expect(
      screen.queryByText(/This mobile number is already registered/i),
    ).not.toBeInTheDocument();
    expect(
      await screen.findByText(/An account with this email already exists/i),
    ).toBeInTheDocument();
    expect(saveBrandCompany).not.toHaveBeenCalled();
  });
});

/**
 * PHONE-0904 Q1 follow-up — Vikram's phone-required-for-brand backend can also return
 * PHONE_REQUIRED and INVALID_PHONE from this same brandRegister call (UserPhoneService /
 * AuthService, same call sites documented on UserService#updateProfile). Same bug class as the
 * duplicate-409s above: both used to fall into the generic step-2 banner instead of landing
 * field-adjacent on step 1.
 */
describe('BrandOnboardingPage — PHONE_REQUIRED / INVALID_PHONE handling (PHONE-0904 Q1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sendBrandEmailOtp.mockResolvedValue({ sent: true });
    verifyBrandEmail.mockResolvedValue({ emailVerified: true });
    saveBrandCompany.mockResolvedValue({});
    checkSlug.mockResolvedValue({ slug: 'acme-co', available: true, suggestions: [] });
  });

  it('a PHONE_REQUIRED 400 lands the user back on step 1 with a field-adjacent message, and does not advance', async () => {
    brandRegister.mockRejectedValue(new ApiError('PHONE_REQUIRED', 'Phone number is required', 400));
    const user = userEvent.setup({ delay: null });
    renderPage();

    await completeStep1(user);
    await submitStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    // Back on step 1 — the mobile field is visible again, step 2's company field is not.
    expect(await screen.findByLabelText(/Mobile number/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Company name/i)).not.toBeInTheDocument();

    const phoneError = await screen.findByText('Mobile number is required');
    expect(phoneError).toBeInTheDocument();
    expect(screen.getByLabelText(/Mobile number/i).closest('div')?.parentElement).toContainElement(
      phoneError,
    );
    expect(saveBrandCompany).not.toHaveBeenCalled();
  });

  it('an INVALID_PHONE 400 lands the user back on step 1 with a field-adjacent message, and does not advance', async () => {
    brandRegister.mockRejectedValue(new ApiError('INVALID_PHONE', 'Phone number is invalid', 400));
    const user = userEvent.setup({ delay: null });
    renderPage();

    await completeStep1(user);
    await submitStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    expect(await screen.findByLabelText(/Mobile number/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Company name/i)).not.toBeInTheDocument();

    const phoneError = await screen.findByText('Enter a valid 10-digit mobile number');
    expect(phoneError).toBeInTheDocument();
    // Distinguishable from the duplicate-phone message.
    expect(
      screen.queryByText(/This mobile number is already registered/i),
    ).not.toBeInTheDocument();
    expect(saveBrandCompany).not.toHaveBeenCalled();
  });
});
