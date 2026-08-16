/**
 * CreatorLayout — header search buttons (CR-122 desktop / CR-124 mobile).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Both the desktop search bar and the mobile search icon in the creator header were dead
 * controls — a styled `<div>` (desktop) and a `<button>` with no `onClick` (mobile). Neither
 * had any test coverage, so the "fix" was unverifiable from CI: a future refactor could
 * silently drop the onClick and nothing would fail. The fix routes both to
 * `/creator/deals`, the one page with a genuinely working "Search brand or campaign…" box —
 * a real button, not a div, so it's keyboard-reachable too.
 *
 * Run: npx vitest run src/components/creator/creator-layout-search.test.tsx
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
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorLayout>
        <div>page content</div>
      </CreatorLayout>
    </MemoryRouter>,
  );
}

describe('CreatorLayout — header search buttons (CR-122 / CR-124)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // No creator_token in localStorage → identity/unread-count hooks stay inert,
    // matching this repo's existing creator-shell test harness convention.
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('CR-124: mobile search icon button navigates to /creator/deals on click', async () => {
    const user = userEvent.setup({ delay: null });
    renderShell();

    // The mobile button is `lg:hidden`; jsdom has no viewport-based CSS evaluation, so both
    // the desktop and mobile buttons are present in the DOM — the mobile one is reachable by
    // its distinct aria-label.
    const mobileSearchButton = screen.getByRole('button', { name: 'Search collaborations' });
    await user.click(mobileSearchButton);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/deals'));
  });

  it('CR-122: desktop search bar is a real, keyboard-reachable button that navigates to /creator/deals', async () => {
    const user = userEvent.setup({ delay: null });
    renderShell();

    const desktopSearchButton = screen.getByRole('button', { name: /search collaborations\.\.\./i });
    await user.click(desktopSearchButton);

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/deals'));
  });
});
