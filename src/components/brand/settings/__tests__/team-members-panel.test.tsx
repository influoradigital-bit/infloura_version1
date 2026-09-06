/**
 * [F-0443, unreachable-endpoint] WorkspaceMemberController was backend-complete and no shipped
 * frontend reached any of it — a brand could not invite a colleague through the product.
 *
 * These tests exercise the real panel against the real `api.workspaceMembers` client surface (only
 * the transport is stubbed), and the last one asserts the panel is actually MOUNTED by the
 * settings page. That last assertion is the one that matters for this finding: a panel that works
 * perfectly but is reachable from nowhere leaves the defect exactly where it was, which is the
 * whole shape of F-0443.
 *
 * Run: npx vitest run src/components/brand/settings/__tests__/team-members-panel.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import fs from 'node:fs';
import { TeamMembersPanel } from '../team-members-panel';
import { toast } from '@/hooks/use-toast';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

const listMock = vi.fn();
const listInvitesMock = vi.fn();
const inviteMock = vi.fn();
const revokeMock = vi.fn();
const removeMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      workspaceMembers: {
        list: (...a: unknown[]) => listMock(...a),
        listInvites: (...a: unknown[]) => listInvitesMock(...a),
        invite: (...a: unknown[]) => inviteMock(...a),
        revokeInvite: (...a: unknown[]) => revokeMock(...a),
        removeMember: (...a: unknown[]) => removeMock(...a),
      },
    },
  };
});

const OWNER = { id: 'm_owner', workspaceId: 'ws_1', userId: 'u_owner', role: 'OWNER', active: true };
const MANAGER = {
  id: 'm_2',
  workspaceId: 'ws_1',
  userId: 'u_manager',
  role: 'MANAGER',
  active: true,
};
const PENDING_INVITE = {
  id: 'inv_1',
  workspaceId: 'ws_1',
  email: 'colleague@example.com',
  role: 'MANAGER',
  status: 'PENDING',
  expiresAt: new Date(Date.now() + 86400000).toISOString(),
};

describe('TeamMembersPanel — F-0443 workspace team management', () => {
  beforeEach(() => {
    [listMock, listInvitesMock, inviteMock, revokeMock, removeMock].forEach((m) => m.mockReset());
    vi.mocked(toast).mockClear();
    localStorage.setItem('brand_user_id', 'u_owner');
    listMock.mockResolvedValue([OWNER, MANAGER]);
    listInvitesMock.mockResolvedValue([PENDING_INVITE]);
    inviteMock.mockResolvedValue({ ...PENDING_INVITE, id: 'inv_new' });
    revokeMock.mockResolvedValue(undefined);
    removeMock.mockResolvedValue(undefined);
  });

  it('lists the workspace members and the outstanding invites', async () => {
    render(<TeamMembersPanel />);

    expect(await screen.findByText('Team members')).toBeInTheDocument();
    expect(screen.getAllByTestId('team-member-row')).toHaveLength(2);
    expect(screen.getByText('colleague@example.com')).toBeInTheDocument();
    expect(screen.getAllByTestId('pending-invite-row')).toHaveLength(1);
  });

  it('sends an invite with the chosen email and role, then refreshes the lists', async () => {
    const user = userEvent.setup();
    render(<TeamMembersPanel />);
    await screen.findByText('Invite a colleague');

    await user.type(screen.getByLabelText('Work email'), 'newhire@company.com');
    await user.click(screen.getByRole('button', { name: /send invite/i }));

    await waitFor(() => expect(inviteMock).toHaveBeenCalledWith('newhire@company.com', 'MEMBER'));
    // The lists must be re-read, or the new invite never appears until a manual reload.
    await waitFor(() => expect(listInvitesMock).toHaveBeenCalledTimes(2));
  });

  it('puts a server-named field error on the email input instead of one generic toast', async () => {
    const user = userEvent.setup();
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    inviteMock.mockRejectedValue(
      new ApiError('VALIDATION_ERROR', 'Request validation failed', 400, undefined, undefined, undefined, [
        { field: 'email', message: 'must be a well-formed email address' },
      ]),
    );
    render(<TeamMembersPanel />);
    await screen.findByText('Invite a colleague');

    await user.type(screen.getByLabelText('Work email'), 'not-an-email@x');
    await user.click(screen.getByRole('button', { name: /send invite/i }));

    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument();
    expect(screen.getByLabelText('Work email')).toHaveAttribute('aria-invalid', 'true');
  });

  it('revokes a pending invite through the real endpoint', async () => {
    const user = userEvent.setup();
    render(<TeamMembersPanel />);
    await screen.findByTestId('pending-invite-row');

    await user.click(screen.getByRole('button', { name: /revoke the invite for colleague@example.com/i }));

    await waitFor(() => expect(revokeMock).toHaveBeenCalledWith('inv_1'));
  });

  it('removes a member, and never offers to remove the OWNER or yourself', async () => {
    const user = userEvent.setup();
    render(<TeamMembersPanel />);
    await screen.findAllByTestId('team-member-row');

    // u_owner is both the OWNER and the signed-in user, so no remove control may exist for it.
    expect(screen.queryByRole('button', { name: /remove u_owner from workspace/i })).toBeNull();

    await user.click(screen.getByRole('button', { name: /remove u_manager from workspace/i }));
    await waitFor(() => expect(removeMock).toHaveBeenCalledWith('m_2'));
  });

  it('says the load failed rather than showing an empty team', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    listMock.mockRejectedValue(new ApiError('SERVER_ERROR', 'Workspace lookup failed', 500));
    render(<TeamMembersPanel />);

    // An empty list with no explanation reads as "you have no team" — the same silence F-0443 is
    // about. The error must be visible.
    expect(await screen.findByText('Workspace lookup failed')).toBeInTheDocument();
  });

  it('is actually MOUNTED by the brand settings page — the whole point of F-0443', () => {
    // A working panel reachable from nowhere leaves the finding exactly where it was. Read as
    // source because rendering the full settings page would pull in every unrelated tab's data
    // loading; `import.meta.url` is not usable for this in the project's vitest config, so the
    // path is resolved from cwd like the other source-guard tests in this repo.
    const page = fs.readFileSync('src/pages/brand-settings.tsx', 'utf8');
    expect(page).toContain("from '@/components/brand/settings/team-members-panel'");
    expect(page).toContain('<TeamMembersPanel />');
    expect(page).toContain('value="team"');
  });
});
