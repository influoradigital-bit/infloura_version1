/**
 * The workspace switcher in the brand account menu.
 *
 * Every brand user is created with a workspace of their own, so anyone who accepts an invite
 * belongs to two — and until this existed nothing listed them, so an invitee had no way into the
 * workspace they had joined (and, once switched, no way back).
 *
 * Run: npx vitest run src/components/brand/__tests__/workspace-switcher.test.tsx
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';

const listMineMock = vi.fn();
const switchMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      workspaces: { ...actual.api.workspaces, listMine: () => listMineMock() },
      workspaceMembers: {
        ...actual.api.workspaceMembers,
        switchWorkspace: (...a: unknown[]) => switchMock(...a),
      },
    },
  };
});

import { WorkspaceSwitcherMenuItems } from '../workspace-switcher';

const OWN = { id: 'ws_own', name: 'Own Co', slug: 'own-co', role: 'OWNER' };
const INVITED = { id: 'ws_invited', name: 'Invited Co', slug: 'invited-co', role: 'MANAGER' };

let queryClient: QueryClient;

function renderMenu(currentWorkspaceId: string) {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/campaigns']}>
        <Routes>
          <Route
            path="/brand/campaigns"
            element={
              <DropdownMenu open>
                <DropdownMenuTrigger>account</DropdownMenuTrigger>
                <DropdownMenuContent>
                  <WorkspaceSwitcherMenuItems currentWorkspaceId={currentWorkspaceId} />
                </DropdownMenuContent>
              </DropdownMenu>
            }
          />
          <Route path="/brand/dashboard" element={<div>dashboard page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkspaceSwitcherMenuItems', () => {
  beforeEach(() => {
    listMineMock.mockReset();
    switchMock.mockReset();
    switchMock.mockResolvedValue(INVITED);
  });

  it('renders nothing for a brand with a single workspace — the menu is unchanged for them', async () => {
    listMineMock.mockResolvedValue([OWN]);
    renderMenu(OWN.id);

    await waitFor(() => expect(listMineMock).toHaveBeenCalled());
    expect(screen.queryByText('Switch workspace')).toBeNull();
  });

  it('lists every workspace and marks the current one', async () => {
    listMineMock.mockResolvedValue([OWN, INVITED]);
    renderMenu(OWN.id);

    expect(await screen.findByText('Switch workspace')).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Own Co/ })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('menuitem', { name: /Invited Co/ })).not.toHaveAttribute('aria-current');
  });

  it('switching swaps the session, drops the cache of the workspace being left, and lands on the dashboard', async () => {
    const user = userEvent.setup();
    listMineMock.mockResolvedValue([OWN, INVITED]);
    renderMenu(OWN.id);
    await screen.findByText('Switch workspace');
    queryClient.setQueryData(['workspace', 'my-role'], 'OWNER');

    await user.click(screen.getByRole('menuitem', { name: /Invited Co/ }));

    await waitFor(() => expect(switchMock).toHaveBeenCalledWith('ws_invited'));
    expect(await screen.findByText('dashboard page')).toBeInTheDocument();
    // A surviving ['workspace','my-role'] = OWNER would gate Invited Co's controls by Own Co's role.
    expect(queryClient.getQueryData(['workspace', 'my-role'])).toBeUndefined();
  });

  it('choosing the workspace you are already in does nothing', async () => {
    const user = userEvent.setup();
    listMineMock.mockResolvedValue([OWN, INVITED]);
    renderMenu(OWN.id);
    await screen.findByText('Switch workspace');

    await user.click(screen.getByRole('menuitem', { name: /Own Co/ }));

    expect(switchMock).not.toHaveBeenCalled();
  });
});
