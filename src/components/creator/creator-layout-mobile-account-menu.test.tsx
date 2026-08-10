/**
 * CreatorLayout — mobile avatar dropdown trigger accessible name (F-0169).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The mobile header's avatar `DropdownMenuTrigger` (`creator-layout.tsx:412`) was icon/avatar
 * only, with no text child and no `aria-label`. Its accessible name was either empty, or — once
 * identity resolved — a bare initials string like "PS", which reads to assistive tech as a
 * name, not a description of what the control does. WCAG 2.1 AA 4.1.2. Fixed with a fixed-string
 * `aria-label="Account menu"` that does not depend on identity having loaded (contrast F-0170,
 * the desktop trigger, whose accessible name genuinely does depend on load state).
 *
 * Run: npx vitest run src/components/creator/creator-layout-mobile-account-menu.test.tsx
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

vi.mock('@/lib/auth-session', async () => {
  const actual = await vi.importActual<typeof import('@/lib/auth-session')>('@/lib/auth-session');
  return {
    ...actual,
    clearCreatorSession: vi.fn(),
    getCreatorSession: () => null,
  };
});

afterEach(() => {
  localStorage.clear();
});

function renderShell(getMeImpl: () => Promise<unknown>) {
  vi.doMock('@/lib/api', async () => {
    const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    return {
      ...actual,
      isApiLive: () => true,
      api: {
        auth: { logout: vi.fn().mockResolvedValue({ message: 'ok' }) },
        creatorProfile: { getMe: getMeImpl },
        deals: { list: vi.fn().mockResolvedValue([]) },
      },
    };
  });
}

describe('CreatorLayout — mobile avatar menu accessible name (F-0169)', () => {
  it('has a real, fixed accessible name before identity has resolved', async () => {
    vi.resetModules();
    renderShell(() => new Promise(() => {})); // never resolves — identity stays loading
    const { CreatorLayout: FreshLayout } = await import('./creator-layout');

    render(
      <MemoryRouter initialEntries={['/creator/dashboard']}>
        <FreshLayout>
          <div>page content</div>
        </FreshLayout>
      </MemoryRouter>,
    );

    // Even with identity unresolved (no displayName, no initials yet), the trigger must still
    // have a real accessible name — this is the exact gap F-0169 closes.
    expect(screen.getByRole('button', { name: 'Account menu' })).toBeInTheDocument();
  });

  it('keeps the same accessible name once identity has resolved (not overridden by initials)', async () => {
    vi.resetModules();
    // useCreatorIdentity's fetch effect early-returns without a `creator_token` — without this,
    // `getMe` is dead code and this test silently exercises the same unresolved state as the
    // "before identity has resolved" test above, proving nothing about the resolved path.
    localStorage.setItem('creator_token', 'test-token');
    renderShell(() =>
      Promise.resolve({ displayName: 'Priya Sharma', username: 'priya_creates', avatarUrl: null }),
    );
    const { CreatorLayout: FreshLayout } = await import('./creator-layout');

    render(
      <MemoryRouter initialEntries={['/creator/dashboard']}>
        <FreshLayout>
          <div>page content</div>
        </FreshLayout>
      </MemoryRouter>,
    );

    // Proves identity genuinely resolved in this render (the desktop sidebar trigger renders
    // the real name once `profile.displayName` lands) — not just that the mobile button's
    // fixed-string aria-label happens to look the same in both states.
    await waitFor(() => expect(screen.getByText('Priya Sharma')).toBeInTheDocument());

    expect(screen.getByRole('button', { name: 'Account menu' })).toBeInTheDocument();
  });

  it('opens the dropdown menu on click (control is still functional, not just labeled)', async () => {
    vi.resetModules();
    renderShell(() => Promise.resolve(null));
    const { CreatorLayout: FreshLayout } = await import('./creator-layout');
    const user = userEvent.setup();

    render(
      <MemoryRouter initialEntries={['/creator/dashboard']}>
        <FreshLayout>
          <div>page content</div>
        </FreshLayout>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Account menu' }));
    expect(await screen.findByText('Profile')).toBeInTheDocument();
  });
});
