/**
 * CreatorLayout — logo click routes home (F-0275).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The logo (desktop sidebar + mobile header) navigated to '/creator/deals' while the
 * sidebar's own "Home" nav item — sitting right next to it — pointed at
 * '/creator/dashboard'. A logo is the universal "take me home" affordance; it should not
 * disagree with the nav item literally labeled "Home". Both logo buttons now route to
 * '/creator/dashboard', matching the Home nav item and the register/onboarding-complete
 * entry points (see creator-login.test.tsx / creator-onboarding.test.tsx).
 *
 * Run: npx vitest run src/components/creator/creator-layout-logo-home.test.tsx
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorLayout } from './creator-layout';

const creatorProfileGetMe = vi.fn().mockResolvedValue(null);
const dealsList = vi.fn().mockResolvedValue([]);

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: { logout: vi.fn().mockResolvedValue({ message: 'ok' }) },
      creatorProfile: { getMe: (...a: unknown[]) => creatorProfileGetMe(...a) },
      deals: { list: (...a: unknown[]) => dealsList(...a) },
    },
  };
});

vi.mock('@/lib/auth-session', async () => {
  const actual = await vi.importActual<typeof import('@/lib/auth-session')>('@/lib/auth-session');
  return {
    ...actual,
    clearCreatorSession: vi.fn(),
    getCreatorSession: () => null,
  };
});

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

function renderShell() {
  return render(
    <MemoryRouter initialEntries={['/creator/deals']}>
      <CreatorLayout>
        <div>page content</div>
      </CreatorLayout>
    </MemoryRouter>,
  );
}

describe('CreatorLayout — logo routes home (F-0275)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('every logo button navigates to /creator/dashboard, not /creator/deals', async () => {
    const user = userEvent.setup({ delay: null });
    renderShell();

    // Desktop sidebar logo + mobile header logo are both in the DOM (jsdom has no
    // viewport-based CSS evaluation); both share the accessible name "Influora".
    const logoButtons = screen.getAllByRole('button', { name: 'Influora' });
    expect(logoButtons.length).toBeGreaterThanOrEqual(1);

    for (const logoButton of logoButtons) {
      navigateMock.mockClear();
      await user.click(logoButton);
      await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/dashboard'));
      expect(navigateMock).not.toHaveBeenCalledWith('/creator/deals');
    }
  });
});
