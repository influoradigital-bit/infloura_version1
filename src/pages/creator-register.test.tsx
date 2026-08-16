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

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/register']}>
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
  await user.type(screen.getByPlaceholderText(/Create a password/i), 'Passw0rdy');
  await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
  await user.click(screen.getByRole('checkbox'));
}

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
