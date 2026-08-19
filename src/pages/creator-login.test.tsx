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

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        creatorLogin: (...a: unknown[]) => creatorLogin(...a),
        setToken: (...a: unknown[]) => setToken(...a),
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
