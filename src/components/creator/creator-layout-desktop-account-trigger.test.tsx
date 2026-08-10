/**
 * CreatorLayout — desktop sidebar account trigger accessible name (F-0170).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The desktop sidebar's account trigger (`creator-layout.tsx:265`,
 * `data-testid="creator-sidebar-account-trigger"`) rendered a skeleton `<span>` with no text
 * while `identity.displayName` was still null — its accessible name was empty during that
 * window (WCAG 2.1 AA 4.1.2). Unlike F-0169's mobile avatar trigger, this button already shows
 * real visible label text once resolved, so a plain `aria-label` would have overridden that
 * text and broken WCAG 2.1 AA 2.5.3 Label in Name instead. Fixed with an unconditional
 * `sr-only` span ("Open account menu") that composes into the accessible name rather than
 * replacing it — non-empty while loading, and the real name is still part of the accessible
 * name once resolved. Wording deliberately differs from F-0169's mobile trigger ("Account
 * menu") since jsdom renders both breakpoint siblings at once with no CSS evaluation.
 *
 * Run: npx vitest run src/components/creator/creator-layout-desktop-account-trigger.test.tsx
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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

describe('CreatorLayout — desktop sidebar account trigger accessible name (F-0170)', () => {
  it('has a non-empty accessible name before identity has resolved', async () => {
    vi.resetModules();
    // No creator_token → useCreatorIdentity's fetch effect never fires (see
    // use-creator-identity.ts:57-61) → identity.displayName stays null → the visible content
    // is the skeleton <span>, no text. This is the exact gap F-0170 closes.
    renderShell(() => new Promise(() => {}));
    const { CreatorLayout: FreshLayout } = await import('./creator-layout');

    render(
      <MemoryRouter initialEntries={['/creator/dashboard']}>
        <FreshLayout>
          <div>page content</div>
        </FreshLayout>
      </MemoryRouter>,
    );

    const trigger = screen.getByTestId('creator-sidebar-account-trigger');
    expect(trigger).toHaveAccessibleName('Open account menu');
  });

  it('accessible name contains the visible display name once identity resolves — not overridden', async () => {
    vi.resetModules();
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

    // Proves identity genuinely resolved in this render before asserting the composed name.
    await waitFor(() => expect(screen.getByText('Priya Sharma')).toBeInTheDocument());

    // WCAG 2.5.3 Label in Name: the accessible name must CONTAIN the visible label text, not
    // replace it with a generic one.
    const trigger = screen.getByTestId('creator-sidebar-account-trigger');
    expect(trigger).toHaveAccessibleName(/Priya Sharma/);
  });

  it('F-0179: composed accessible name excludes the decorative avatar initials', async () => {
    vi.resetModules();
    localStorage.setItem('creator_token', 'test-token');
    // 'Priya Sharma' -> initials 'PS' would leak in as redundant letters if the Avatar weren't
    // marked aria-hidden — assert the exact composed name, not just a substring match, so a
    // regression that reintroduces the initials ("Open account menu PS Priya Sharma") is caught.
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

    await waitFor(() => expect(screen.getByText('Priya Sharma')).toBeInTheDocument());

    const trigger = screen.getByTestId('creator-sidebar-account-trigger');
    expect(trigger).toHaveAccessibleName('Open account menu Priya Sharma');
  });
});
