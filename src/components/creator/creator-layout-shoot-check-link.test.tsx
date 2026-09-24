/**
 * Shoot Check shipped to production on 2026-09-24 with no link anywhere: the page existed and
 * worked, but nothing in the sidebar, the dashboard or Meera pointed at it, so the only way in
 * was typing the URL. Found by the same-day contract audit ("dead or unreachable UI").
 *
 * Run: npx vitest run src/components/creator/creator-layout-shoot-check-link.test.tsx
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
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

/** Renders wherever the router currently is, so a nav tap can be checked by destination. */
function LocationProbe() {
  const { pathname } = useLocation();
  return <div data-testid="pathname">{pathname}</div>;
}

describe('CreatorLayout — Shoot Check is reachable', () => {
  it('has a Shoot Check entry in the creator navigation that goes to the page', async () => {
    // Nav entries are buttons that navigate (not anchors), and the desktop sidebar is CSS-hidden
    // in jsdom, so this reads the same navGroups through the mobile sheet - the route it lands on
    // is what actually matters.
    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter initialEntries={['/creator/dashboard']}>
        <CreatorLayout>
          <LocationProbe />
        </CreatorLayout>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    const entry = await screen.findByRole('button', { name: /shoot check/i });
    await user.click(entry);

    await waitFor(() =>
      expect(screen.getByTestId('pathname').textContent).toBe('/creator/shoot-check'),
    );
  });
});
