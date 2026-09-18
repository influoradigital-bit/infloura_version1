/**
 * [F-0443] Invite redemption — `POST /workspace/members/accept`.
 *
 * Without this page the Team tab is only half a feature: a brand could send an invite nobody could
 * accept, so "a brand cannot invite a colleague through the product" would still be true. These
 * tests cover the three states that actually differ in the product — no token, no session, and a
 * server rejection — because each one used to be a dead end.
 *
 * Run: npx vitest run src/pages/__tests__/brand-accept-invite.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import fs from 'node:fs';
import BrandAcceptInvitePage from '../brand-accept-invite';

const acceptMock = vi.fn();
const switchMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      workspaceMembers: {
        acceptInvite: (...a: unknown[]) => acceptMock(...a),
        switchWorkspace: (...a: unknown[]) => switchMock(...a),
      },
    },
  };
});

let queryClient: QueryClient;

function LoginProbe() {
  const location = useLocation();
  return <div>login page {location.search}</div>;
}

function renderAt(path: string) {
  queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/brand/invite" element={<BrandAcceptInvitePage />} />
          <Route path="/brand/login" element={<LoginProbe />} />
          <Route path="/brand/dashboard" element={<div>dashboard page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BrandAcceptInvitePage — F-0443 invite redemption', () => {
  beforeEach(() => {
    acceptMock.mockReset();
    acceptMock.mockResolvedValue({
      id: 'm_new',
      workspaceId: 'ws_1',
      userId: 'u_2',
      role: 'MANAGER',
      active: true,
    });
    switchMock.mockReset();
    switchMock.mockResolvedValue({ id: 'ws_1', name: 'Acme Co', slug: 'acme-co', role: 'MANAGER' });
    localStorage.setItem('brand_token', 'mock_brand_token');
  });

  it('redeems the token and confirms the join', async () => {
    const user = userEvent.setup();
    renderAt('/brand/invite?token=tok_abc');

    await user.click(screen.getByRole('button', { name: /accept invite/i }));

    await waitFor(() => expect(acceptMock).toHaveBeenCalledWith('tok_abc'));
    expect(await screen.findByText('You have joined Acme Co')).toBeInTheDocument();
  });

  it('ENTERS the joined workspace — accepting alone leaves the session in the workspace the invitee already owns', async () => {
    const user = userEvent.setup();
    renderAt('/brand/invite?token=tok_abc');
    // Anything cached so far belongs to the workspace being left.
    queryClient.setQueryData(['workspace', 'my-role'], 'OWNER');

    await user.click(screen.getByRole('button', { name: /accept invite/i }));

    // The id comes from the accept RESPONSE (the membership row), never from the URL.
    await waitFor(() => expect(switchMock).toHaveBeenCalledWith('ws_1'));
    expect(acceptMock.mock.invocationCallOrder[0]).toBeLessThan(switchMock.mock.invocationCallOrder[0]);
    await screen.findByText('You have joined Acme Co');
    expect(queryClient.getQueryData(['workspace', 'my-role'])).toBeUndefined();
  });

  it('a redeemed invite whose switch failed is reported as joined, with the way in — not as a failed invite', async () => {
    const user = userEvent.setup();
    switchMock.mockRejectedValue(new Error('network down'));
    renderAt('/brand/invite?token=tok_abc');

    await user.click(screen.getByRole('button', { name: /accept invite/i }));

    expect(await screen.findByText('You have joined the workspace')).toBeInTheDocument();
    expect(screen.getByText(/could not switch you into it/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /accept invite/i })).toBeNull();
  });

  it('never calls the endpoint when there is no session — it can only 401', async () => {
    localStorage.removeItem('brand_token');
    renderAt('/brand/invite?token=tok_abc');

    expect(screen.getByText('Sign in to join this workspace')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /accept invite/i })).toBeNull();
    expect(acceptMock).not.toHaveBeenCalled();
  });

  it('carries the invite token through the login round trip', async () => {
    const user = userEvent.setup();
    localStorage.removeItem('brand_token');
    renderAt('/brand/invite?token=tok_abc');

    await user.click(screen.getByRole('button', { name: /sign in to continue/i }));
    // Losing the token at the login hop would strand the invitee on a page they cannot complete.
    expect(
      await screen.findByText(`login page ?next=${encodeURIComponent('/brand/invite?token=tok_abc')}`),
    ).toBeInTheDocument();
  });

  it('shows the server reason a revoked or expired invite was refused', async () => {
    const user = userEvent.setup();
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    acceptMock.mockRejectedValue(new ApiError('INVITE_REVOKED', 'This invite was revoked', 409));
    renderAt('/brand/invite?token=tok_abc');

    await user.click(screen.getByRole('button', { name: /accept invite/i }));

    // Revoked, expired and already-used are indistinguishable without the server's own message.
    expect(await screen.findByText('This invite was revoked')).toBeInTheDocument();
  });

  it('explains a link that arrived without a token instead of failing silently', () => {
    renderAt('/brand/invite');

    expect(screen.getByText('This invite link is incomplete')).toBeInTheDocument();
    expect(acceptMock).not.toHaveBeenCalled();
  });

  it('is actually ROUTED — an unrouted page is unreachable, which is the finding', () => {
    const app = fs.readFileSync('src/App.tsx', 'utf8');
    expect(app).toContain("from '@/pages/brand-accept-invite'");
    expect(app).toContain('path="/brand/invite"');
  });
});
