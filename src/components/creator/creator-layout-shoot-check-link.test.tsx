/**
 * History: Shoot Check shipped on 2026-09-24 with no link anywhere, so a "Shoot Check" nav row
 * was added. On 2026-09-26 the photo check moved INTO Meera's chat (SPEC §4): the page is gone,
 * /creator/shoot-check redirects to /creator/copilot?camera=1, and the nav row goes with it.
 * The Co-pilot row is shown as "Meera" (RULINGS 3: "Co-pilot" is a Microsoft name; the route
 * stays /creator/copilot).
 *
 * The redirect itself is proved through the real App router in
 * src/pages/creator-copilot.camera-param.test.tsx (this file mocks `@/lib/api` down to what the
 * layout needs, which the whole App cannot render with).
 *
 * Run: npx vitest run src/components/creator/creator-layout-shoot-check-link.test.tsx
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
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

async function openMobileMenu() {
  // Nav entries are buttons that navigate (not anchors), and the desktop sidebar is CSS-hidden
  // in jsdom, so this reads the same navGroups through the mobile sheet.
  const user = userEvent.setup({ delay: null });
  render(
    <MemoryRouter initialEntries={['/creator/dashboard']}>
      <CreatorLayout>
        <LocationProbe />
      </CreatorLayout>
    </MemoryRouter>,
  );
  await user.click(screen.getByRole('button', { name: 'Open menu' }));
  const sheet = await screen.findByRole('dialog');
  return { user, sheet };
}

describe('CreatorLayout — photo check lives in Meera', () => {
  it('has no Shoot Check entry in the creator navigation', async () => {
    const { sheet } = await openMobileMenu();

    // Positive control first: the sheet really holds the nav (so the absence below is not vacuous).
    expect(within(sheet).getByRole('button', { name: /^deals$/i })).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: /shoot check/i })).not.toBeInTheDocument();
    expect(sheet.textContent).not.toMatch(/shoot check/i);
  });

  it('shows the Co-pilot entry as "Meera", going to /creator/copilot, with no "Co-pilot" text', async () => {
    const { user, sheet } = await openMobileMenu();

    expect(sheet.textContent).not.toMatch(/co-?pilot/i);
    await user.click(within(sheet).getByRole('button', { name: /^meera$/i }));

    await waitFor(() => expect(screen.getByTestId('pathname').textContent).toBe('/creator/copilot'));
  });
});
