/**
 * Creator registration — email-OTP gate.
 *
 * Covers the hole the 2026-07-26 audit found: `POST /auth/creator/send-email-otp` and
 * `/auth/creator/verify-email` existed in AuthController with ZERO frontend clients, so turning
 * `influora.auth.require-email-otp-before-register` on would have made every creator signup 400
 * with nothing in the UI able to satisfy it.
 *
 * The two cases below are the contract:
 *   - flag off  → form submits straight to creatorRegister, no extra step in the funnel
 *   - flag on   → send OTP → verify → THEN creatorRegister, in that order
 *
 * Run: npx vitest run src/pages/creator-register.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
// Not mocked: `vi.mock('@/lib/api')` below spreads the real module, so this is the genuine
// ApiError class the page's `err instanceof ApiError` checks are written against.
import { ApiError } from '@/lib/api';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorRegisterPage from './creator-register';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ login: vi.fn(), logout: vi.fn(), user: null }),
}));

const sendCreatorEmailOtp = vi.fn();
const verifyCreatorEmail = vi.fn();
const creatorRegister = vi.fn();
const publicConfig = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        sendCreatorEmailOtp: (...a: unknown[]) => sendCreatorEmailOtp(...a),
        verifyCreatorEmail: (...a: unknown[]) => verifyCreatorEmail(...a),
        creatorRegister: (...a: unknown[]) => creatorRegister(...a),
        setToken: vi.fn(),
      },
      config: { public: () => publicConfig() },
    },
  };
});

function renderPage(initialEntry = '/creator/register') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <CreatorRegisterPage />
    </MemoryRouter>,
  );
}

/**
 * Fills every field the page validates, so submit is never blocked by validation.
 * Queried by placeholder rather than label — the two password labels both match a
 * /Password/ pattern, and their show/hide toggles carry matching aria-labels.
 */
async function fillForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('Enter your full name'), 'Test Creator');
  await user.type(screen.getByPlaceholderText('you@example.com'), 'creator@example.com');
  // PHONE-0906 - mandatory since creator phone capture moved from onboarding step 2 to signup.
  // Omitting it here does not merely weaken a test, it blocks submit outright.
  await user.type(screen.getByPlaceholderText('98765 43210'), '9876500001');
  await user.type(screen.getByPlaceholderText(/Create a password/i), 'Passw0rdy');
  await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
  await user.click(screen.getByRole('checkbox'));
}

/**
 * PHONE-0906 - the mobile number is REQUIRED at creator signup (previously it was an optional
 * field one screen later, on onboarding step 2). These cover the three things that can go wrong
 * and that no type-check or build would catch:
 *   - the field is genuinely gating submit, not merely rendered (the F-0341 dead-control class);
 *   - the wire carries the NORMALIZED 10 digits, not the raw '+91 ...' the input holds;
 *   - a server-side rejection lands ON the field, and does not burn a second OTP.
 */
describe('CreatorRegisterPage - required mobile number (PHONE-0906)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
    sendCreatorEmailOtp.mockResolvedValue({
      message: 'sent',
      expiresIn: 300,
      maskedEmail: 'c***@example.com',
    });
    verifyCreatorEmail.mockResolvedValue({ emailVerified: true, message: 'ok' });
    creatorRegister.mockResolvedValue({ token: 't', userId: 'cr_1', onboardingComplete: false });
  });

  it('blocks submit and shows an inline error when the mobile number is empty', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    // Everything EXCEPT the phone, so the phone is provably the only thing holding submit.
    await user.type(screen.getByPlaceholderText('Enter your full name'), 'Test Creator');
    await user.type(screen.getByPlaceholderText('you@example.com'), 'creator@example.com');
    await user.type(screen.getByPlaceholderText(/Create a password/i), 'Passw0rdy');
    await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    expect(await screen.findByText('Mobile number is required')).toBeInTheDocument();
    expect(creatorRegister).not.toHaveBeenCalled();
  });

  it('rejects a malformed number client-side, before any network call', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await user.type(screen.getByPlaceholderText('Enter your full name'), 'Test Creator');
    await user.type(screen.getByPlaceholderText('you@example.com'), 'creator@example.com');
    // Starts with 1 - fails the shared [6-9]\d{9} rule that IndianPhoneUtils enforces server-side.
    await user.type(screen.getByPlaceholderText('98765 43210'), '1234567890');
    await user.type(screen.getByPlaceholderText(/Create a password/i), 'Passw0rdy');
    await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    expect(await screen.findByText('Enter a valid 10-digit mobile number')).toBeInTheDocument();
    expect(creatorRegister).not.toHaveBeenCalled();
  });

  it('normalizes a pasted +91 number to 10 digits on the wire', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await user.type(screen.getByPlaceholderText('Enter your full name'), 'Test Creator');
    await user.type(screen.getByPlaceholderText('you@example.com'), 'creator@example.com');
    // The single most common way an Indian number is stored in a contacts app.
    await user.type(screen.getByPlaceholderText('98765 43210'), '+91 98765 00001');
    await user.type(screen.getByPlaceholderText(/Create a password/i), 'Passw0rdy');
    await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    await waitFor(() => expect(creatorRegister).toHaveBeenCalledTimes(1));
    expect(creatorRegister.mock.calls[0][0].phone).toBe('9876500001');
  });

  it('puts a duplicate-number 409 on the phone field, not in the generic banner', async () => {
    creatorRegister.mockRejectedValueOnce(
      new ApiError('PHONE_ALREADY_EXISTS', 'An account with this phone number already exists', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    // The wording points at support on purpose: users.phone_number is UNIQUE across BOTH user
    // types and has no self-serve release path, so "already registered" alone is a dead end.
    expect(await screen.findByText(/already registered/i)).toBeInTheDocument();
    // And it must not be mislabelled as an email conflict, which shares the 409 status.
    expect(screen.queryByText(/email already exists/i)).not.toBeInTheDocument();
  });

  it('does not send a second OTP when a phone rejection bounces the user back to the form', async () => {
    // The rate-limit trap this guards: a phone conflict is only knowable server-side, i.e. AFTER
    // the OTP was verified. Re-sending on every retry walks the user into
    // BrandEmailOtpService's per-email hourly RATE_LIMITED on a form they cannot yet submit.
    publicConfig.mockResolvedValue({ requireEmailOtp: true });
    creatorRegister.mockRejectedValueOnce(
      new ApiError('PHONE_ALREADY_EXISTS', 'An account with this phone number already exists', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));
    await waitFor(() => expect(sendCreatorEmailOtp).toHaveBeenCalledTimes(1));

    // Same driving as the gate test above: 6 one-char boxes that auto-advance, so a single
    // type() on the first fills all of them and enables Verify.
    expect(await screen.findByText(/Verify your email/i)).toBeInTheDocument();
    const boxes = screen.getAllByRole('textbox').filter((el) => el.getAttribute('maxlength') === '1');
    await user.type(boxes[0], '123456');
    await user.click(screen.getByRole('button', { name: /Verify email/i }));
    await waitFor(() => expect(creatorRegister).toHaveBeenCalledTimes(1));

    // Back on the form with the error on the field; fix the number and resubmit.
    const phoneInput = await screen.findByPlaceholderText('98765 43210');
    await user.clear(phoneInput);
    await user.type(phoneInput, '9876500002');
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    await waitFor(() => expect(creatorRegister).toHaveBeenCalledTimes(2));
    // The email was already verified in this session - exactly one OTP, not two.
    expect(sendCreatorEmailOtp).toHaveBeenCalledTimes(1);
  });
});

describe('CreatorRegisterPage — email OTP gate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sendCreatorEmailOtp.mockResolvedValue({ message: 'sent', expiresIn: 300, maskedEmail: 'c***@example.com' });
    verifyCreatorEmail.mockResolvedValue({ emailVerified: true, message: 'ok' });
    creatorRegister.mockResolvedValue({ token: 't', userId: 'cr_1', onboardingComplete: false });
  });

  it('registers directly when the server does not require OTP', async () => {
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    await waitFor(() => expect(creatorRegister).toHaveBeenCalledTimes(1));
    // No OTP step was inserted into the funnel.
    expect(sendCreatorEmailOtp).not.toHaveBeenCalled();
    expect(screen.queryByText(/Verify your email/i)).not.toBeInTheDocument();
  });

  it('sends and verifies an OTP before registering when the server requires it', async () => {
    publicConfig.mockResolvedValue({ requireEmailOtp: true });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    // Code goes out and the gate takes over — crucially, registration has NOT fired yet.
    await waitFor(() =>
      expect(sendCreatorEmailOtp).toHaveBeenCalledWith('creator@example.com'),
    );
    expect(await screen.findByText(/Verify your email/i)).toBeInTheDocument();
    expect(creatorRegister).not.toHaveBeenCalled();

    // Typing the 6th digit enables Verify; the boxes auto-advance so one type() fills them all.
    const boxes = screen.getAllByRole('textbox').filter((el) => el.getAttribute('maxlength') === '1');
    expect(boxes).toHaveLength(6);
    await user.type(boxes[0], '123456');

    await user.click(screen.getByRole('button', { name: /Verify email/i }));

    await waitFor(() =>
      expect(verifyCreatorEmail).toHaveBeenCalledWith('creator@example.com', '123456'),
    );
    // Only now does the account get created.
    await waitFor(() => expect(creatorRegister).toHaveBeenCalledTimes(1));
  });

  it('keeps the user on the form when the OTP cannot be delivered', async () => {
    publicConfig.mockResolvedValue({ requireEmailOtp: true });
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    sendCreatorEmailOtp.mockRejectedValue(
      new ApiError('EMAIL_DELIVERY_FAILED', 'Unable to send verification email. Try again later.', 503),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    // The failure surfaces on the form the user is looking at, not on an empty OTP panel with
    // no code ever arriving.
    expect(await screen.findByText(/Unable to send verification email/i)).toBeInTheDocument();
    expect(screen.queryByText(/Verify your email/i)).not.toBeInTheDocument();
    expect(creatorRegister).not.toHaveBeenCalled();
  });
});

/**
 * Q5.5 (T-CREATORCONNECT-0902, Medium) — the invite claim path.
 *
 * The invite email's signup_url (AdminCreatorConnectionService#sendJoinInvitationEmail) carries
 * `?ref=influora-invite&handle={igUsername}&invite_token={signed token}`. Before this fix the
 * page never read `searchParams` at all, so `inviteToken` was always null on the wire, and
 * AuthService#creatorRegister -> RegistrationService#consumeInviteToken was unreachable for any
 * invited creator who registered by email — the external_creators row stayed INVITED forever.
 * This pins that `invite_token` reaches the POST body, and that an ordinary (non-invite)
 * registration is unaffected.
 */
describe('CreatorRegisterPage — invite claim (Q5.5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
    creatorRegister.mockResolvedValue({ token: 't', userId: 'cr_1', onboardingComplete: false });
  });

  it('forwards the invite_token query param to creatorRegister', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage('/creator/register?ref=influora-invite&handle=foodie.mumbai&invite_token=signed-abc123');
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    // The handle is shown as a human-readable hint...
    expect(await screen.findByText(/You were invited as @foodie\.mumbai/i)).toBeInTheDocument();

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    // ...but it is the signed token, not the bare handle, that is sent as the claim.
    await waitFor(() =>
      expect(creatorRegister).toHaveBeenCalledWith(
        expect.objectContaining({ inviteToken: 'signed-abc123' }),
      ),
    );
  });

  it('registers with no inviteToken when there is no invite in the URL', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage('/creator/register');
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    expect(screen.queryByText(/You were invited/i)).not.toBeInTheDocument();

    await fillForm(user);
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    await waitFor(() => expect(creatorRegister).toHaveBeenCalledTimes(1));
    expect(creatorRegister.mock.calls[0][0].inviteToken).toBeUndefined();
  });

  it('does not show the invite banner when handle is present without the expected ref', async () => {
    // A stray ?handle= must never itself be treated as a claim (Q5.4's spoof class) — the
    // banner (and the claim) require ref=influora-invite to have come from the real email.
    // (No userEvent setup: this case asserts on the initial render only, it types nothing.)
    renderPage('/creator/register?handle=someone&invite_token=signed-abc123');
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    expect(screen.queryByText(/You were invited/i)).not.toBeInTheDocument();
  });
});
