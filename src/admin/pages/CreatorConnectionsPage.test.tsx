/**
 * INFLUORA ADMIN PANEL — Creator Connections Page Tests
 * Owner: Ananya (Frontend)
 * Reference: T-CREATORCONNECT-0902
 *
 * Focus: the Invite dialog's email gate — `POST /admin/creator-connections/:id/invite` requires
 * a valid email server-side, and the FE must not let the admin submit without one. Also Q4.1:
 * apiRequest resolves { success: false, error } on a non-2xx response (api-contracts.ts:95-105)
 * rather than throwing, and fetch() itself can reject on a network failure — both must surface
 * in the dialog and keep it open instead of closing as if the action succeeded.
 *
 * Run: npx vitest run src/admin/pages/CreatorConnectionsPage.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render as rtlRender, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import CreatorConnectionsPage from './CreatorConnectionsPage';
import type { AdminConnection } from '../types/admin.types';

const toastFn = vi.fn();
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastFn }) }));

function render(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return rtlRender(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

const listMock = vi.fn();
const externalCreatorsMock = vi.fn();
const markContactedMock = vi.fn();
const declineMock = vi.fn();
const inviteMock = vi.fn();
const importHandlesMock = vi.fn();

vi.mock('../services/api-contracts', async () => {
  const actual = await vi.importActual<typeof import('../services/api-contracts')>(
    '../services/api-contracts',
  );
  return {
    ...actual,
    creatorConnectionsApi: {
      list: (...a: unknown[]) => listMock(...a),
      get: vi.fn(),
      markContacted: (...a: unknown[]) => markContactedMock(...a),
      decline: (...a: unknown[]) => declineMock(...a),
      invite: (...a: unknown[]) => inviteMock(...a),
      externalCreators: (...a: unknown[]) => externalCreatorsMock(...a),
      importHandles: (...a: unknown[]) => importHandlesMock(...a),
    },
  };
});

const PENDING_CONNECTION: AdminConnection = {
  id: 'ccr_001',
  status: 'PENDING',
  message: 'Would love to collaborate on our Diwali launch.',
  adminNotes: null,
  workspaceId: 'ws_1',
  brandName: 'Aarohi Foods',
  requestedByUserId: 'u_1',
  requestedByEmail: 'brand@example.com',
  externalCreatorId: 'ec_1',
  igUsername: 'foodie.mumbai',
  displayName: 'Foodie Mumbai',
  avatarUrl: null,
  followers: 42000,
  creatorEmail: null,
  creatorStatus: 'UNVERIFIED',
  linkedCreatorProfileId: null,
  createdAt: '2026-09-01T10:00:00Z',
  handledAt: null,
  joinedNotifiedAt: null,
};

describe('CreatorConnectionsPage — Invite dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    toastFn.mockClear();
    listMock.mockResolvedValue({
      success: true,
      data: { items: [PENDING_CONNECTION], page: 1, pageSize: 20, total: 1 },
    });
    externalCreatorsMock.mockResolvedValue({
      success: true,
      data: { items: [], page: 1, pageSize: 20, total: 0 },
    });
    inviteMock.mockResolvedValue({
      success: true,
      data: { ...PENDING_CONNECTION, status: 'CONTACTED', creatorStatus: 'INVITED' },
    });
  });

  it('disables the invite submit button until a valid email is entered', async () => {
    const user = userEvent.setup({ delay: null });
    render(<CreatorConnectionsPage />);

    await waitFor(() => expect(listMock).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /^invite$/i }));

    const dialog = await screen.findByRole('dialog');
    const submit = screen.getAllByRole('button', { name: /send invitation/i }).at(-1)!;
    expect(submit).toBeDisabled();

    const emailInput = dialog.querySelector('#cc-invite-email') as HTMLInputElement;
    await user.type(emailInput, 'not-an-email');
    expect(submit).toBeDisabled();

    await user.clear(emailInput);
    await user.type(emailInput, 'creator@example.com');
    expect(submit).toBeEnabled();

    await user.click(submit);

    await waitFor(() =>
      expect(inviteMock).toHaveBeenCalledWith('ccr_001', { email: 'creator@example.com', notes: undefined }),
    );
  });

  it('shows the "Invitation sent to {email}" toast and closes the dialog on a real success (Q4.1)', async () => {
    const user = userEvent.setup({ delay: null });
    render(<CreatorConnectionsPage />);

    await waitFor(() => expect(listMock).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /^invite$/i }));

    const dialog = await screen.findByRole('dialog');
    const emailInput = dialog.querySelector('#cc-invite-email') as HTMLInputElement;
    await user.type(emailInput, 'creator@example.com');
    await user.click(screen.getAllByRole('button', { name: /send invitation/i }).at(-1)!);

    await waitFor(() => expect(inviteMock).toHaveBeenCalled());
    await waitFor(() =>
      expect(toastFn).toHaveBeenCalledWith(
        expect.objectContaining({ description: 'Invitation sent to creator@example.com.' }),
      ),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('surfaces res.error and keeps the dialog open when apiRequest resolves { success: false } (Q4.1)', async () => {
    inviteMock.mockResolvedValue({ success: false, error: 'This creator already has a pending invite.' });
    const user = userEvent.setup({ delay: null });
    render(<CreatorConnectionsPage />);

    await waitFor(() => expect(listMock).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /^invite$/i }));

    const dialog = await screen.findByRole('dialog');
    const emailInput = dialog.querySelector('#cc-invite-email') as HTMLInputElement;
    await user.type(emailInput, 'creator@example.com');
    await user.click(screen.getAllByRole('button', { name: /send invitation/i }).at(-1)!);

    await waitFor(() => expect(inviteMock).toHaveBeenCalled());
    // Dialog stays open with the server-reported reason visible, not closed as if it succeeded.
    expect(await screen.findByRole('alert')).toHaveTextContent('This creator already has a pending invite.');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(toastFn).not.toHaveBeenCalled();
  });

  it('surfaces a network failure via onError and keeps the dialog open (Q4.1)', async () => {
    inviteMock.mockRejectedValue(new Error('Failed to fetch'));
    const user = userEvent.setup({ delay: null });
    render(<CreatorConnectionsPage />);

    await waitFor(() => expect(listMock).toHaveBeenCalled());
    await user.click(await screen.findByRole('button', { name: /^invite$/i }));

    const dialog = await screen.findByRole('dialog');
    const emailInput = dialog.querySelector('#cc-invite-email') as HTMLInputElement;
    await user.type(emailInput, 'creator@example.com');
    await user.click(screen.getAllByRole('button', { name: /send invitation/i }).at(-1)!);

    await waitFor(() => expect(inviteMock).toHaveBeenCalled());
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to fetch');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(toastFn).not.toHaveBeenCalled();
  });
});

// [Q4.5] The Java record (AdminCreatorConnectionService.java:675/677/679/684) emits null for
// brandName, requestedByEmail, igUsername and creatorStatus whenever the owning workspace, user,
// or external-creator row has been deleted. The table must render an honest fallback for each
// instead of crashing (c.igUsername.charAt(0) previously threw and blanked the whole table via
// the error boundary).
describe('CreatorConnectionsPage — null admin-connection fields (Q4.5)', () => {
  const NULL_FIELDS_CONNECTION: AdminConnection = {
    id: 'ccr_null',
    status: 'PENDING',
    message: null,
    adminNotes: null,
    workspaceId: 'ws_deleted',
    brandName: null,
    requestedByUserId: 'u_deleted',
    requestedByEmail: null,
    externalCreatorId: 'ec_deleted',
    igUsername: null,
    displayName: null,
    avatarUrl: null,
    followers: null,
    creatorEmail: null,
    creatorStatus: null,
    linkedCreatorProfileId: null,
    createdAt: '2026-09-01T10:00:00Z',
    handledAt: null,
    joinedNotifiedAt: null,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    toastFn.mockClear();
    listMock.mockResolvedValue({
      success: true,
      data: { items: [NULL_FIELDS_CONNECTION], page: 1, pageSize: 20, total: 1 },
    });
    externalCreatorsMock.mockResolvedValue({
      success: true,
      data: { items: [], page: 1, pageSize: 20, total: 0 },
    });
  });

  it('renders a row with null brandName/requestedByEmail/igUsername/creatorStatus without crashing, using em-dash fallbacks', async () => {
    render(<CreatorConnectionsPage />);

    await waitFor(() => expect(listMock).toHaveBeenCalled());

    // brandName em-dash fallback (was previously rendered raw / would render "null"). Waits for
    // the row to actually mount — if the render had thrown, this would time out instead of
    // finding anything, since the error boundary would blank the table.
    const brandCells = await screen.findAllByText('—');
    expect(brandCells.length).toBeGreaterThan(0);

    // No error boundary swallowed the table: the "no requests" empty state must NOT be shown.
    expect(
      screen.queryByText('No creator connection requests match the current filters.'),
    ).not.toBeInTheDocument();

    // igUsername guarded: no "@null" and no thrown TypeError from charAt on null.
    expect(screen.queryByText('@null')).not.toBeInTheDocument();
    expect(screen.queryByText(/^@/)).not.toBeInTheDocument();

    // Avatar fallback renders a safe placeholder glyph instead of calling charAt on null.
    expect(screen.getByText('?')).toBeInTheDocument();
  });
});
