/**
 * F-0779 (caller 2) — brand-onboarding.tsx must send a normalized phone to
 * api.auth.brandRegister on the cold-entry path.
 *
 * WHY THIS FILE EXISTS. F-0780's gate
 * (.proof-os/gates/F-0780-brand-register-sends-phone.sh) claims BOTH callers of
 * BrandRegisterPayload put a usable phone on the wire. Its caller-2 leg used to run
 * src/components/brand/onboarding/__tests__/onboarding-steps.persistence.test.tsx, which
 * asserts F-0394/F-0395/F-0396 (Terms checkbox, company-size vocabulary, logo upload),
 * never references `brandRegister`, and never renders this page — so the gate's second leg
 * proved nothing about caller 2's payload. kavya falsified that by hand: with
 * brand-onboarding.tsx:94 mutated from `phone: normalizePhone(data.phone)` to `phone: ''`,
 * the gate still exited 0 and still printed its "BOTH callers" banner, and `tsc --noEmit`
 * stayed green too because '' is a legal string for the now-required field. That is F-0779
 * — a gate watching one caller while a sibling caller is broken — reproduced inside the
 * remediation for F-0779. This suite is the real caller-2 assertion.
 *
 * WHAT IT PINS. The wizard is driven from a COLD entry — localStorage is cleared so
 * `hasBrandToken()` (src/lib/auth-session.ts:309) is false, brand-onboarding.tsx:38 starts
 * at step 1, and the `!hasBrandToken()` branch at :72 actually performs the registration.
 * A warm entry (step 2, token present) skips brandRegister entirely and could never observe
 * the payload, which is precisely the hole this file closes.
 *
 * The assertion is on the VALUE that reaches the mocked client, not the presence of a key:
 * `phone: ''` and `phone: '+91 98765 43210'` both satisfy `'phone' in payload` and are both
 * rejected on the wire by IndianPhoneUtils (PHONE_REQUIRED/400 and INVALID_PHONE/400
 * respectively — AuthService.brandRegister). The expected value is derived from
 * src/lib/phone.ts, never re-derived here.
 *
 * Run: npx vitest run src/pages/__tests__/brand-onboarding-sends-phone.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import BrandOnboardingPage from '../brand-onboarding';
import { normalizePhone, isValidPhone } from '@/lib/phone';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const sendBrandEmailOtp = vi.fn();
const verifyBrandEmail = vi.fn();
const brandRegister = vi.fn();
const saveBrandCompany = vi.fn();
const completeBrand = vi.fn();
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
        setToken: vi.fn(),
      },
      onboarding: {
        saveBrandCompany: (...a: unknown[]) => saveBrandCompany(...a),
        completeBrand: (...a: unknown[]) => completeBrand(...a),
      },
      workspaces: { checkSlug: (...a: unknown[]) => checkSlug(...a) },
    },
    ApiError: actual.ApiError,
  };
});

vi.mock('@/hooks/use-toast', () => ({ toast: vi.fn() }));

/** What a user actually pastes out of their contacts. Must survive to the wire as 10 digits. */
const RAW_PHONE = '+91 98765 43210';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/brand/onboarding']}>
        <BrandOnboardingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Locate the step-1 mobile input without name-locking to one spelling — same four
 * identification routes as the caller-1 suite, and the same defect-naming throw when the
 * field is absent altogether.
 */
function getPhoneInput(): HTMLInputElement {
  const byLabel = screen.queryAllByLabelText(/mobile|phone/i);
  if (byLabel.length > 0) return byLabel[0] as HTMLInputElement;

  const byPlaceholder = screen.queryAllByPlaceholderText(/98765|mobile|phone/i);
  if (byPlaceholder.length > 0) return byPlaceholder[0] as HTMLInputElement;

  const byAttr = document.querySelector<HTMLInputElement>(
    'input[inputmode="tel"], input[type="tel"], input[name*="phone" i], input[id*="phone" i]',
  );
  if (byAttr) return byAttr;

  throw new Error(
    'F-0779/caller-2: no mobile/phone input is rendered in step 1 of brand-onboarding.tsx. ' +
      'BrandRegisterRequest requires phone, so this caller cannot complete a signup.',
  );
}

type User = ReturnType<typeof userEvent.setup>;

/** Step 1 stage A — the account form. Leaves the OTP screen on screen. */
async function fillAccountStep(user: User, phone: string) {
  await user.type(screen.getByPlaceholderText('Rahul'), 'Priya');
  await user.type(screen.getByPlaceholderText('Sharma'), 'Sharma');
  await user.type(screen.getByPlaceholderText('rahul@company.com'), 'brand@example.com');
  if (phone) await user.type(getPhoneInput(), phone);
  await user.type(screen.getByPlaceholderText('Min 8 characters'), 'Passw0rdy');
  await user.type(screen.getByPlaceholderText('Re-enter password'), 'Passw0rdy');
  await user.click(screen.getByRole('checkbox'));
  await user.click(screen.getByRole('button', { name: /Send verification code/i }));
}

/** Step 1 stage B — the 6-box OTP screen. Pasting is handled by OtpInput's own onPaste. */
async function passEmailOtp(user: User) {
  await screen.findByText(/Verify your email/i);
  const boxes = document.querySelectorAll<HTMLInputElement>('input[inputmode="numeric"]');
  expect(boxes.length).toBe(6);
  await user.click(boxes[0]);
  await user.paste('123456');
  await user.click(screen.getByRole('button', { name: /Verify email/i }));
}

/**
 * Step 2 — company details, then Continue. Continue is what calls
 * `handleCompanySaveAndNext`, which is where brandRegister is invoked on a cold entry.
 */
async function fillCompanyStepAndContinue(user: User) {
  await screen.findByText(/Tell us about your brand/i);
  await user.type(screen.getByPlaceholderText('Acme India Pvt Ltd'), 'Audit Test Brand');

  // Neither Select's <Label> carries an htmlFor, so these have no accessible name to query
  // by; they are positional in the same grid — Industry first, Company size second.
  const combos = screen.getAllByRole('combobox');
  expect(combos.length).toBe(2);

  await user.click(combos[0]);
  await user.click(await screen.findByRole('option', { name: 'Technology' }));

  await user.click(combos[1]);
  await user.click(await screen.findByRole('option', { name: /Startup/i }));

  // The slug availability check is debounced 400ms and a 'taken' verdict blocks validate();
  // wait for the mocked 'available' verdict to land so Continue is not refused for that.
  await waitFor(() => expect(checkSlug).toHaveBeenCalled());

  await user.click(screen.getByRole('button', { name: /Continue/i }));
}

describe('BrandOnboardingPage — phone reaches api.auth.brandRegister (F-0779, caller 2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Cold entry: no brand_token, so hasBrandToken() is false, the wizard starts at step 1,
    // and brand-onboarding.tsx:72 takes the branch that actually registers.
    localStorage.clear();
    sendBrandEmailOtp.mockResolvedValue({ sent: true });
    verifyBrandEmail.mockResolvedValue({ emailVerified: true });
    brandRegister.mockResolvedValue({ token: 't', userId: 'u_1', onboardingComplete: false });
    saveBrandCompany.mockResolvedValue({});
    completeBrand.mockResolvedValue({});
    checkSlug.mockResolvedValue({ slug: 'audit-test-brand', available: true, suggestions: [] });
  });

  it('starts at step 1 on a cold entry, so the register call is actually made', async () => {
    renderPage();
    // If this ever renders step 2 instead, every payload assertion below becomes vacuous —
    // the warm path skips brandRegister entirely. Pin the entry condition explicitly.
    expect(await screen.findByText(/Create your account/i)).toBeInTheDocument();
    expect(getPhoneInput()).toBeInTheDocument();
  });

  it('sends the NORMALIZED 10-digit phone, not the raw pasted string', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await fillAccountStep(user, RAW_PHONE);
    await passEmailOtp(user);
    await fillCompanyStepAndContinue(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    const payload = brandRegister.mock.calls[0][0] as Record<string, unknown>;
    // Guard the guard: if this stops being 10 digits the shared rule changed, not the page.
    const expected = normalizePhone(RAW_PHONE);
    expect(isValidPhone(expected)).toBe(true);

    expect(payload.phone).toBeDefined();
    // The point of the record: `phone: ''` and `phone: '+91 98765 43210'` both survive a
    // toBeDefined()/'in payload' check and both 400 at IndianPhoneUtils. Assert the wire value.
    expect(String(payload.phone)).toMatch(/^\d{10}$/);
    expect(payload.phone).toBe(expected);
  });

  it('blocks client-side and fires NO register call when phone is left empty', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    // Prove the field exists before asserting the empty-submit path, so a wizard with no
    // phone input at all fails HERE rather than passing a "no call was made" check.
    const phone = getPhoneInput();
    expect(phone).toHaveValue('');

    await fillAccountStep(user, '');

    // A blank phone must never reach the wire — the server would only answer PHONE_REQUIRED.
    expect(sendBrandEmailOtp).not.toHaveBeenCalled();
    expect(brandRegister).not.toHaveBeenCalled();
    expect(screen.getByText(/Required/i)).toBeInTheDocument();
  });
});
