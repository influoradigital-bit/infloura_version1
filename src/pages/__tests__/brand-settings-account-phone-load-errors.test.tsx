/**
 * Brand Settings — GET /users/me load failures must be distinguishable, not collapsed (F-0636).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The account "Mobile Number" card's load failure used to render the exact same
 * "Could not load your mobile number." string, with the exact same Retry button, for an expired
 * session (401/403), a real server error (5xx / SERVER_UNAVAILABLE), and the request never
 * reaching the network at all (fetch() rejecting outright — never wrapped in ApiError, see
 * HttpClient#request in src/lib/api.ts). Retry is actively useless on the auth case: by the time
 * this catch block sees a 401, `fetchWithAuthRetry` has already attempted one silent
 * refresh+retry, so pressing the same Retry button just 401s again — the user needs to sign back
 * in. `classifyAccountPhoneLoadFailure` (brand-settings.tsx) now maps each case to its own copy
 * and an explicit `retryable` flag the UI branches on (Sign In vs Retry).
 *
 * HARNESS NOTES — mirrors brand-settings-account-phone.test.tsx's `vi.mock('@/lib/api', ...)`
 * shape (mock mode disabled — `isApiLive: () => true` — so the effect always calls through the
 * mocked api.users.getMe()).
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings-account-phone-load-errors.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandSettingsPage from '../brand-settings';
import { ApiError } from '@/lib/api';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ logout: vi.fn() }),
}));

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

const workspacesGetMe = vi.fn();
const usersGetMe = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      workspaces: {
        getMe: (...a: unknown[]) => workspacesGetMe(...a),
        updateMe: vi.fn().mockResolvedValue({}),
      },
      workspaceMembers: {
        list: vi.fn().mockResolvedValue([]),
        invite: vi.fn().mockResolvedValue({ id: 'inv_1' }),
      },
      notifications: {
        getPreferences: vi.fn().mockResolvedValue([]),
        setPreference: vi.fn().mockResolvedValue({ ok: true }),
      },
      auth: {
        logout: vi.fn().mockResolvedValue({ message: 'ok' }),
        changePassword: vi.fn().mockResolvedValue({ changed: true }),
      },
      users: {
        getMe: (...a: unknown[]) => usersGetMe(...a),
        updateMe: vi.fn().mockResolvedValue({}),
      },
    },
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/settings']}>
      <BrandSettingsPage />
    </MemoryRouter>,
  );
}

async function openSecurityTab() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('tab', { name: /Security/i }));
}

describe('BrandSettingsPage — Mobile Number load failures are distinguishable (F-0636)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    workspacesGetMe.mockResolvedValue({
      id: 'ws_1',
      name: 'Tech Brands Co.',
      slug: 'tech-brands-co',
      email: 'ops@techbrands.in',
      phone: null,
      industry: null,
      companySize: null,
      websiteUrl: null,
      description: null,
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });
  });

  it('an expired session (401) gets its own message and a Sign In action, not Retry', async () => {
    usersGetMe.mockRejectedValue(new ApiError('UNAUTHENTICATED', 'Token expired', 401));
    renderPage();
    await openSecurityTab();

    expect(
      await screen.findByText('Your session has expired. Please sign in again to view your mobile number.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();

    const signIn = screen.getByRole('button', { name: 'Sign In' });
    const user = userEvent.setup();
    await user.click(signIn);
    expect(navigateMock).toHaveBeenCalledWith('/brand/login');
  });

  it('a server error (500) gets its own message and a Retry action', async () => {
    usersGetMe.mockRejectedValue(new ApiError('INTERNAL_ERROR', 'boom', 500));
    renderPage();
    await openSecurityTab();

    expect(
      await screen.findByText(
        "Something went wrong on our end. This isn't a problem with your account — please try again in a moment.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign In' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('a dropped network connection (fetch rejecting, not an ApiError) gets its own offline message', async () => {
    // This is exactly what a real network failure looks like at this call site: fetch() itself
    // throws, so the rejection is a plain Error/TypeError, never an ApiError (see
    // HttpClient#request in src/lib/api.ts — the try/catch that builds ApiError only wraps
    // responses that actually came back from the server).
    usersGetMe.mockRejectedValue(new TypeError('Failed to fetch'));
    renderPage();
    await openSecurityTab();

    expect(
      await screen.findByText('You appear to be offline. Check your connection and try again.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign In' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('an unrecognized ApiError falls back to the pre-existing generic message with a Retry action', async () => {
    // Belt-and-suspenders: any failure this endpoint doesn't otherwise return (an odd 4xx, say)
    // still gets a real message and a Retry, never an unhandled/blank state.
    usersGetMe.mockRejectedValue(new ApiError('WEIRD_CODE', 'unexpected', 418));
    renderPage();
    await openSecurityTab();

    expect(await screen.findByText('Could not load your mobile number.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('Retry on the offline case re-invokes GET /users/me and recovers once it succeeds', async () => {
    usersGetMe.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    renderPage();
    await openSecurityTab();
    await screen.findByText('You appear to be offline. Check your connection and try again.');

    usersGetMe.mockResolvedValueOnce({
      id: 'user_1',
      email: 'admin@techbrands.in',
      displayName: 'Amit Singh',
      firstName: 'Amit',
      lastName: 'Singh',
      userType: 'BRAND',
      status: 'ACTIVE',
      avatarUrl: null,
      emailVerified: true,
      phoneVerified: false,
      phone: '9000000001',
      timezone: null,
      createdAt: new Date().toISOString(),
    });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(usersGetMe).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('+91 9000000001')).toBeInTheDocument();
  });
});
