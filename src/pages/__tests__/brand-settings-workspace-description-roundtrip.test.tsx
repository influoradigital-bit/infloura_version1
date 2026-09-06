/**
 * [F-0468, partial-fix-narrows-defect] Saving workspace settings must not clear the brand bio.
 *
 * F-0462 established that `PATCH /workspaces/me` is FULL-REPLACE — an omitted field is CLEARED,
 * not left alone — and carried three of the four unsurfaced fields forward. `description` was the
 * fourth and stayed omitted, so every settings save silently wiped the brand bio captured at
 * onboarding. It needed a second fix on the READ side too: `description` was write-only, so there
 * was nothing to echo back even if the payload had included it.
 *
 * WHY THE FAKE SERVER HERE REPLACES RATHER THAN MERGES. That is the real contract
 * (`WorkspaceService#updateMyWorkspace` -> `Workspace#applyCompanyDetails` assigns every field
 * unconditionally). A mock that merged would pass whether or not the client sent the field, which
 * is precisely the assertion that would have let F-0468 through: the defect is invisible unless
 * the double is as unforgiving as production.
 *
 * Run: npx vitest run src/pages/__tests__/brand-settings-workspace-description-roundtrip.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandSettingsPage from '../brand-settings';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => vi.fn() };
});

vi.mock('@/lib/store', () => ({ useAuthStore: () => ({ logout: vi.fn() }) }));
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

/** The server's row. Mutated by the fake PATCH exactly the way full-replace really behaves. */
let serverWorkspace: Record<string, unknown>;

const workspacesGetMe = vi.fn();
const workspacesUpdateMe = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      workspaces: {
        getMe: (...a: unknown[]) => workspacesGetMe(...a),
        updateMe: (...a: unknown[]) => workspacesUpdateMe(...a),
      },
      workspaceMembers: {
        list: vi.fn().mockResolvedValue([]),
        listInvites: vi.fn().mockResolvedValue([]),
        invite: vi.fn().mockResolvedValue({ id: 'inv_1' }),
        revokeInvite: vi.fn().mockResolvedValue(undefined),
        removeMember: vi.fn().mockResolvedValue(undefined),
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
        getMe: vi.fn().mockResolvedValue({ phoneNumber: '9123456780' }),
        updateMe: vi.fn().mockResolvedValue({ phoneNumber: '9123456780' }),
      },
    },
  };
});

const BIO = 'India-first D2C skincare, founded 2021.';

describe('BrandSettingsPage — F-0468 a workspace save must not clear the brand bio', () => {
  beforeEach(() => {
    serverWorkspace = {
      id: 'ws_1',
      name: 'Tech Brands',
      slug: 'tech-brands',
      email: 'hello@techbrands.in',
      phone: '9123456780',
      industry: 'Beauty',
      companySize: '11-50',
      websiteUrl: 'https://techbrands.in',
      description: BIO,
      logoUrl: 'https://cdn.example/logo.png',
      verificationStatus: 'VERIFIED',
    };
    workspacesGetMe.mockReset();
    workspacesUpdateMe.mockReset();
    workspacesGetMe.mockImplementation(async () => ({ ...serverWorkspace }));
    // FULL-REPLACE, matching Workspace#applyCompanyDetails: every field in the payload is
    // assigned, and a field the client omits lands as undefined — i.e. cleared.
    workspacesUpdateMe.mockImplementation(async (payload: Record<string, unknown>) => {
      serverWorkspace = {
        ...serverWorkspace,
        name: payload.name,
        email: payload.email,
        phone: payload.phone,
        websiteUrl: payload.websiteUrl,
        industry: payload.industry,
        companySize: payload.companySize,
        description: payload.description,
        logoUrl: payload.logoUrl,
      };
      return { ...serverWorkspace };
    });
  });

  async function saveWorkspaceName(newName: string) {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <BrandSettingsPage />
      </MemoryRouter>,
    );
    // Save is gated on the load completing (workspaceInfoLoaded), so wait for the real value.
    const nameInput = await screen.findByDisplayValue('Tech Brands');
    await user.clear(nameInput);
    await user.type(nameInput, newName);
    const saveButtons = await screen.findAllByRole('button', { name: /save changes/i });
    await user.click(saveButtons[0]);
    await waitFor(() => expect(workspacesUpdateMe).toHaveBeenCalled());
  }

  it('carries the bio through a save that only edits the workspace name', async () => {
    await saveWorkspaceName('Tech Brands India');

    const sent = workspacesUpdateMe.mock.calls[0][0] as Record<string, unknown>;
    expect(sent.name).toBe('Tech Brands India');
    // The payload must actually CONTAIN the bio — omitting it is the defect, and under
    // full-replace an omitted field is indistinguishable from an intentional clear.
    expect(sent.description).toBe(BIO);
    expect(serverWorkspace.description).toBe(BIO);
  });

  it('carries the other three unsurfaced fields too — F-0462 fixed these, F-0468 must not undo them', async () => {
    await saveWorkspaceName('Tech Brands India');

    const sent = workspacesUpdateMe.mock.calls[0][0] as Record<string, unknown>;
    expect(sent.industry).toBe('Beauty');
    expect(sent.companySize).toBe('11-50');
    expect(sent.logoUrl).toBe('https://cdn.example/logo.png');
    expect(serverWorkspace.industry).toBe('Beauty');
    expect(serverWorkspace.companySize).toBe('11-50');
    expect(serverWorkspace.logoUrl).toBe('https://cdn.example/logo.png');
  });
});
