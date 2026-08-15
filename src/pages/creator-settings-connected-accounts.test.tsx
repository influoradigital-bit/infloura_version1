/**
 * CreatorSettingsPage — ConnectedAccounts is actually mounted (CR-101 / F-0114).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * `ConnectedAccounts` (src/components/creator/connected-accounts.tsx) was a fully built
 * component with zero imports anywhere in src/ — a creator visiting Settings saw no Connected
 * Accounts card, no connection status, and no way to see what's linked at all. Fixed by
 * importing and rendering it on the Settings page. This file pins the MOUNT, not the
 * component's internal behavior (connected-accounts.test.tsx already covers that in isolation).
 *
 * Run: npx vitest run src/pages/creator-settings-connected-accounts.test.tsx
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import CreatorSettingsPage from './creator-settings';

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      auth: { logout: vi.fn().mockResolvedValue({ message: 'ok' }) },
      notifications: {
        getPreferences: vi.fn().mockResolvedValue([]),
        setPreference: vi.fn().mockResolvedValue({ ok: true }),
      },
      me: { deleteAccount: vi.fn() },
      creatorProfile: { getMe: vi.fn().mockResolvedValue(null) },
      metaOAuth: {
        getLocalConnectionState: vi.fn().mockReturnValue({ connected: true, scopes: ['instagram_basic'], accountType: 'business' }),
        status: vi.fn().mockResolvedValue({ connected: true, grantedScopes: ['instagram_basic'] }),
        setLocalConnectionState: vi.fn(),
        authorize: vi.fn(),
      },
    },
  };
});

function renderSettings() {
  return render(
    <MemoryRouter initialEntries={['/creator/settings']}>
      <CreatorSettingsPage />
    </MemoryRouter>,
  );
}

describe('CreatorSettingsPage — ConnectedAccounts mount (CR-101 / F-0114)', () => {
  it('renders the Connected Accounts card', async () => {
    renderSettings();

    expect(await screen.findByText('Connected Accounts')).toBeInTheDocument();
    expect(screen.getByText('Instagram')).toBeInTheDocument();
    expect(screen.getByText('Facebook Page')).toBeInTheDocument();
  });

  it('reflects real connection status from useMetaConnection, not a static placeholder', async () => {
    renderSettings();

    // Two "Connected" badges — one per platform row — proving this reads live state rather
    // than always showing the disconnected/"Connect" CTA regardless of backend truth.
    expect(await screen.findAllByText('Connected')).toHaveLength(2);
  });
});
