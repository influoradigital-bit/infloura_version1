/**
 * F-0780 — brand-register.tsx must send a normalized phone to api.auth.brandRegister.
 *
 * F-0392 added a REQUIRED phone component to BrandRegisterRequest and AuthService.brandRegister
 * now throws PHONE_REQUIRED/400 on a blank one. Only one of the DTO's two callers was updated:
 * the onboarding wizard (src/components/brand/onboarding) sends phone; THIS page — the signup
 * CTA linked from the landing page, every /features page, both how-it-works pages and the blog —
 * has no phone state, no phone input, and omits phone from its payload. `tsc` is blind to it
 * because BrandRegisterPayload.phone is declared optional (src/lib/api.ts:994) while
 * CreatorRegisterPayload.phone is required (:1016).
 *
 * F-0779 is the reason this file exists as a SEPARATE suite rather than an extra case in the
 * onboarding one: the gate that closed F-0392 asserted phone persistence through the wizard
 * caller only, and stayed green for the whole window this caller was broken.
 *
 * The assertions are deliberately on the VALUE that reaches the mocked client, not on the
 * presence of a key: sending the raw '+91 98765 43210' the input holds would satisfy
 * `phone !== undefined` and still be rejected on the wire by IndianPhoneUtils. The expected
 * value is derived from src/lib/phone.ts (`normalizePhone`), never re-derived here.
 *
 * Run: npx vitest run src/pages/__tests__/brand-register-sends-phone.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandRegisterPage from '../brand-register';
import { normalizePhone, isValidPhone } from '@/lib/phone';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const sendBrandEmailOtp = vi.fn();
const verifyBrandEmail = vi.fn();
const brandRegister = vi.fn();
const publicConfig = vi.fn();

// Same mocking idiom as src/pages/brand-register.test.tsx — spread the real module so ApiError
// and every untouched helper stay real, and replace only the `api` object's auth/config surface.
vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        sendBrandEmailOtp: (...a: unknown[]) => sendBrandEmailOtp(...a),
        verifyBrandEmail: (...a: unknown[]) => verifyBrandEmail(...a),
        brandRegister: (...a: unknown[]) => brandRegister(...a),
        setToken: vi.fn(),
      },
      config: { public: () => publicConfig() },
    },
  };
});

/** What a user actually pastes out of their contacts. Must survive to the wire as 10 digits. */
const RAW_PHONE = '+91 98765 43210';

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/register']}>
      <BrandRegisterPage />
    </MemoryRouter>,
  );
}

/**
 * Locate the Step 2 mobile-number input without name-locking to one spelling. Tries the four
 * ways this field is legitimately identifiable (the creator-register.tsx precedent uses all of
 * them: <Label htmlFor="phone">Mobile Number</Label>, id="phone", inputMode="tel",
 * placeholder="98765 43210"), and throws a message naming the DEFECT when none of them exist —
 * which is what a caller with no phone field at all looks like.
 */
function getPhoneInput(): HTMLInputElement {
  const byLabel = screen.queryAllByLabelText(/mobile|phone/i);
  if (byLabel.length > 0) return byLabel[0] as HTMLInputElement;

  const byPlaceholder = screen.queryAllByPlaceholderText(/98765|mobile|phone/i);
  if (byPlaceholder.length > 0) return byPlaceholder[0] as HTMLInputElement;

  const byAttr = document.querySelector<HTMLInputElement>(
    'input[inputmode="tel"], input[type="tel"], input[name*="phone" i], input[id*="phone" i], input[autocomplete*="tel" i]',
  );
  if (byAttr) return byAttr;

  throw new Error(
    'F-0780: no mobile/phone input is rendered in Step 2 of brand-register.tsx. ' +
      'BrandRegisterRequest requires phone (AuthService.brandRegister throws PHONE_REQUIRED/400 ' +
      'on a blank one), so this caller cannot complete a signup.',
  );
}

/**
 * The smallest DOM subtree that contains the phone input and no other input — i.e. the phone
 * FIELD, not the whole form. Used to prove a server-side phone error lands beside the field
 * rather than only in the generic `errors.form` banner at the top of the form (the banner's
 * container is the <form>, which holds every other input, so it can never satisfy this).
 */
function phoneFieldScope(input: HTMLElement): HTMLElement {
  let scope: HTMLElement = input.parentElement ?? input;
  let cursor: HTMLElement | null = input.parentElement;
  while (cursor && cursor.querySelectorAll('input').length === 1) {
    scope = cursor;
    cursor = cursor.parentElement;
  }
  return scope;
}

/** Step 1 is company details behind two Radix selects; step 2 is the credentials form. */
async function completeStep1(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText(/brand or company name/i), 'Audit Test Brand');

  await user.click(screen.getByRole('combobox', { name: /Industry/i }));
  await user.click(await screen.findByRole('option', { name: 'Technology' }));

  await user.click(screen.getByRole('combobox', { name: /Team Size/i }));
  await user.click(await screen.findByRole('option', { name: /6–20 people/ }));

  await user.click(screen.getByRole('button', { name: /^Next$/i }));
}

/** Fills every Step 2 field EXCEPT phone, and does not submit. */
async function fillStep2WithoutPhone(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('Jane'), 'Priya');
  await user.type(screen.getByPlaceholderText('Doe'), 'Sharma');
  await user.type(screen.getByPlaceholderText('you@company.com'), 'brand@example.com');
  await user.type(screen.getByPlaceholderText(/Create a strong password/i), 'Passw0rdy');
  await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
  await user.click(screen.getByRole('checkbox'));
}

async function submitStep2(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /Create Account/i }));
}

describe('BrandRegisterPage — phone reaches api.auth.brandRegister (F-0780)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // OTP off for every case here: this suite is about the payload, and brand-register.test.tsx
    // already owns the OTP-gate behaviour.
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
    sendBrandEmailOtp.mockResolvedValue({ message: 'sent', expiresIn: 300, maskedEmail: 'b***@example.com' });
    verifyBrandEmail.mockResolvedValue({ emailVerified: true, message: 'ok' });
    brandRegister.mockResolvedValue({ token: 't', userId: 'u_1', onboardingComplete: false });
  });

  it('sends the NORMALIZED 10-digit phone, not the raw pasted string', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);
    await fillStep2WithoutPhone(user);
    await user.type(getPhoneInput(), RAW_PHONE);
    await submitStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    const payload = brandRegister.mock.calls[0][0] as Record<string, unknown>;
    // Guard the guard: if this ever stops being 10 digits the shared rule changed, not the page.
    const expected = normalizePhone(RAW_PHONE);
    expect(isValidPhone(expected)).toBe(true);

    expect(payload.phone).toBeDefined();
    // The point of the record: `phone: '+91 98765 43210'` would pass a toBeDefined() check and
    // still 400 at IndianPhoneUtils. Assert the wire value.
    expect(String(payload.phone)).toMatch(/^\d{10}$/);
    expect(payload.phone).toBe(expected);
  });

  it('blocks client-side and fires NO network call when phone is left empty', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);
    await fillStep2WithoutPhone(user);
    // Prove the field exists before asserting the empty-submit path, so a page with no phone
    // input at all fails HERE (missing field) rather than passing a "no call was made" check.
    const phone = getPhoneInput();
    expect(phone).toHaveValue('');

    await submitStep2(user);

    // A blank phone must never cost a round trip — the server would only answer PHONE_REQUIRED.
    await waitFor(() => expect(screen.getByRole('button', { name: /Create Account/i })).toBeEnabled());
    expect(brandRegister).not.toHaveBeenCalled();
    expect(sendBrandEmailOtp).not.toHaveBeenCalled();
    expect(navigateMock).not.toHaveBeenCalledWith('/brand/onboarding');

    // And the user has to be told which field stopped them. Matched on the validation wording
    // rather than on "phone" — the field's own <Label> also says "Mobile Number", so a bare
    // /mobile|phone/ inside this scope would pass with no error rendered at all.
    const scope = phoneFieldScope(phone);
    expect(within(scope).getByText(/required|valid/i)).toBeInTheDocument();
  });

  it('puts a PHONE_ALREADY_EXISTS rejection on the phone field, not only in the form banner', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    brandRegister.mockRejectedValue(
      new ApiError('PHONE_ALREADY_EXISTS', 'That mobile number is already registered', 409),
    );

    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);
    await fillStep2WithoutPhone(user);
    const phone = getPhoneInput();
    await user.type(phone, RAW_PHONE);
    await submitStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    // users.phone_number is UNIQUE across BOTH user types, so this is a real outcome for a brand
    // whose number is already on a creator account. The message has to sit next to the field the
    // user must change — a generic banner at the top of the form leaves them guessing.
    const scope = await waitFor(() => {
      const s = phoneFieldScope(getPhoneInput());
      expect(within(s).getByText(/already registered/i)).toBeInTheDocument();
      return s;
    });
    expect(scope.contains(getPhoneInput())).toBe(true);
    expect(navigateMock).not.toHaveBeenCalledWith('/brand/onboarding');
  });
});
