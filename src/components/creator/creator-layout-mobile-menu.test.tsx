/**
 * CreatorLayout — mobile hamburger menu button accessible name (F-0167).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The mobile hamburger button (`creator-layout.tsx:344`) toggled `mobileMenuOpen` and swapped
 * its icon (Menu/X) correctly, but had no `aria-label` and no text child — its only content was
 * a lucide icon rendered `aria-hidden="true"`. A screen reader announced it as an unnamed
 * "button", a WCAG 2.1 AA 4.1.2 failure. Found by a fresh-context review of an adjacent fix
 * (CR-124), logged as ledger F-0167, fixed by giving the button a real aria-label that tracks
 * open/closed state, plus aria-expanded.
 *
 * Run: npx vitest run src/components/creator/creator-layout-mobile-menu.test.tsx
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CreatorLayout } from './creator-layout';

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: { logout: vi.fn().mockResolvedValue({ message: 'ok' }) },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
      deals: { list: vi.fn().mockResolvedValue([]) },
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

function renderShell() {
  return render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorLayout>
        <div>page content</div>
      </CreatorLayout>
    </MemoryRouter>,
  );
}

describe('CreatorLayout — mobile hamburger menu accessible name (F-0167)', () => {
  afterEach(() => {
    localStorage.clear();
  });

  it('has a real accessible name in the closed state, discoverable by role', () => {
    renderShell();

    const menuButton = screen.getByRole('button', { name: 'Open menu' });
    expect(menuButton).toHaveAttribute('aria-expanded', 'false');
  });

  it('accessible name and aria-expanded flip to the open state on click', async () => {
    const user = userEvent.setup();
    renderShell();

    // Hold a stable reference to the button itself, not a role query — once the mobile nav
    // Sheet opens, Radix's focus-trap correctly marks the rest of the header aria-hidden (real
    // accessibility behavior, not a regression), so a post-click getByRole('button', ...) would
    // no longer find it even though the button and its label are still right.
    const menuButton = screen.getByRole('button', { name: 'Open menu' });
    await user.click(menuButton);

    expect(menuButton).toHaveAttribute('aria-label', 'Close menu');
    expect(menuButton).toHaveAttribute('aria-expanded', 'true');
  });

  it('closing the sheet (Escape) reverts the accessible name and aria-expanded', async () => {
    const user = userEvent.setup();
    renderShell();

    // The hamburger itself is the only way to OPEN the sheet; once open, Radix's overlay sets
    // `pointer-events: none` on the rest of the page (correct real-browser behavior — a user
    // can't click through a modal), so a real user closes it via Escape, the Sheet's own close
    // button, or an overlay click, not by clicking the now-unclickable hamburger again. Both
    // `mobileMenuOpen` and the Sheet share the same state via `onOpenChange={setMobileMenuOpen}`,
    // so closing via Escape must flip the hamburger's label back too.
    const menuButton = screen.getByRole('button', { name: 'Open menu' });
    await user.click(menuButton);
    expect(menuButton).toHaveAttribute('aria-label', 'Close menu');

    await user.keyboard('{Escape}');

    expect(menuButton).toHaveAttribute('aria-label', 'Open menu');
    expect(menuButton).toHaveAttribute('aria-expanded', 'false');
  });
});
