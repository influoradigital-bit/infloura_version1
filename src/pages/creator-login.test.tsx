/**
 * Creator Login — post-login destination (F-0275).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * A returning creator with onboarding already complete used to land on '/creator/inbox',
 * which App.tsx redirects to '/creator/deals?status=new' — a text-only empty state with no
 * call to action when there are no pending proposals. '/creator/dashboard' carries the real
 * zero-state (creator-dashboard.tsx's `isEmptyCreator` branch) with working CTAs. This test
 * pins the login redirect to that destination so a future edit can't silently regress it
 * back to the dead-end inbox route.
 *
 * Run: npx vitest run src/pages/creator-login.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorLoginPage from './creator-login';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const loginMock = vi.fn();
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ login: loginMock, logout: vi.fn(), user: null }),
}));

const creatorLogin = vi.fn();
const setToken = vi.fn();
const sendCreatorEmailOtp = vi.fn();
const verifyCreatorEmail = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        creatorLogin: (...a: unknown[]) => creatorLogin(...a),
        setToken: (...a: unknown[]) => setToken(...a),
        sendCreatorEmailOtp: (...a: unknown[]) => sendCreatorEmailOtp(...a),
        verifyCreatorEmail: (...a: unknown[]) => verifyCreatorEmail(...a),
      },
    },
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/login']}>
      <CreatorLoginPage />
    </MemoryRouter>,
  );
}

async function submitLogin(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Email Address'), 'creator@example.com');
  await user.type(screen.getByLabelText('Password'), 'Passw0rdy');
  await user.click(screen.getByRole('button', { name: /Sign In/i }));
}

describe('CreatorLoginPage — post-login destination (F-0275)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('routes an onboarded creator to /creator/dashboard, not the dead-end /creator/inbox', async () => {
    creatorLogin.mockResolvedValue({
      token: 't',
      userId: 'cr_1',
      email: 'creator@example.com',
      displayName: 'Test Creator',
      onboardingComplete: true,
    });
    const user = userEvent.setup({ delay: null });
    renderPage();

    await submitLogin(user);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/dashboard'));
    expect(navigateMock).not.toHaveBeenCalledWith('/creator/inbox');
  });

  it('still sends a creator who has not finished onboarding to /creator/onboarding', async () => {
    creatorLogin.mockResolvedValue({
      token: 't',
      userId: 'cr_2',
      email: 'creator@example.com',
      displayName: 'Test Creator',
      onboardingComplete: false,
    });
    const user = userEvent.setup({ delay: null });
    renderPage();

    await submitLogin(user);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/onboarding'));
  });
});

/**
 * F-0601 — the EMAIL_NOT_VERIFIED dead end.
 *
 * A creator who signed up while `require-email-otp-before-register` was off (its default, and
 * what production ran) got an account stamped PENDING_VERIFICATION that no email had ever been
 * sent for. AuthService.creatorLogin (AuthService.java:412) then rejected every later sign-in
 * with EMAIL_NOT_VERIFIED, and this page rendered that message as a flat error with no control
 * able to clear it — the account was unrecoverable through the UI.
 *
 * These click all the way through the recovery, because a panel that renders but whose buttons
 * do nothing passes tsc, eslint and a screenshot review alike.
 */
describe('CreatorLoginPage — unverified-email recovery (F-0601)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  async function rejectAsUnverified() {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    creatorLogin.mockRejectedValueOnce(
      new ApiError('EMAIL_NOT_VERIFIED', 'Please verify your email before signing in', 403),
    );
  }

  it('offers a way to verify instead of a dead-end error, and signs in once verified', async () => {
    await rejectAsUnverified();
    sendCreatorEmailOtp.mockResolvedValue({
      message: 'OTP sent successfully',
      expiresIn: 300,
      maskedEmail: 'c***@example.com',
    });
    verifyCreatorEmail.mockResolvedValue({ emailVerified: true, message: 'Email verified' });
    // The retry after verification succeeds — the server has promoted the account to ACTIVE.
    creatorLogin.mockResolvedValueOnce({
      token: 't',
      userId: 'cr_3',
      email: 'creator@example.com',
      displayName: 'Test Creator',
      onboardingComplete: true,
    });

    const user = userEvent.setup({ delay: null });
    renderPage();
    await submitLogin(user);

    // The 403 must NOT surface as a plain message the user can do nothing about.
    expect(await screen.findByText(/Verify your email/i)).toBeInTheDocument();
    expect(screen.queryByText('Please verify your email before signing in')).toBeNull();

    await user.click(screen.getByRole('button', { name: /Send verification code/i }));
    await waitFor(() =>
      expect(sendCreatorEmailOtp).toHaveBeenCalledWith('creator@example.com'),
    );

    const boxes = screen.getAllByRole('textbox').filter((el) => el.getAttribute('maxlength') === '1');
    expect(boxes).toHaveLength(6);
    await user.type(boxes[0], '123456');
    await user.click(screen.getByRole('button', { name: /Verify email/i }));

    await waitFor(() =>
      expect(verifyCreatorEmail).toHaveBeenCalledWith('creator@example.com', '123456'),
    );
    // The whole point: the login is retried and now lands the creator in the app.
    await waitFor(() => expect(creatorLogin).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/dashboard'));
  });

  it('lets the user back out to the sign-in form', async () => {
    await rejectAsUnverified();
    const user = userEvent.setup({ delay: null });
    renderPage();
    await submitLogin(user);

    expect(await screen.findByText(/Verify your email/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Back to sign in/i }));

    expect(await screen.findByLabelText('Email Address')).toBeInTheDocument();
  });

  it('still shows an ordinary error inline — only EMAIL_NOT_VERIFIED opens the panel', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    creatorLogin.mockRejectedValueOnce(
      new ApiError('INVALID_CREDENTIALS', 'Invalid email or password', 401),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await submitLogin(user);

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Send verification code/i })).toBeNull();
  });
});
