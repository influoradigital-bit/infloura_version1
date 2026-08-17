/**
 * useWorkspaceVerification — F-0244 regression gate (owner-self-lockout).
 *
 * Before the fix, `isLoading`/`canVerify` were derived from the `me` query only and ignored
 * the separate `['workspace','my-role']` query entirely. Whenever that role query was still in
 * flight, had rejected, or had nothing to match (`brand_user_id` missing from localStorage),
 * `memberRole` stayed `null`, so `canVerify` was `false` and every consumer rendered the
 * terminal "Only an Owner or Admin can submit workspace verification" — including to the sole
 * workspace OWNER, with no retry and no error state.
 *
 * These cases must fail OPEN (canVerify: true, roleResolved: false) instead: role query
 * pending, role query rejecting, and `brand_user_id` missing. Only a genuinely *resolved*,
 * confirmed non-admin role may produce `canVerify: false`.
 *
 * Run: npx vitest run src/hooks/brand/__tests__/useWorkspaceVerification.test.ts
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    api: {
      ...actual.api,
      workspaces: {
        ...actual.api.workspaces,
        getMe: vi.fn(),
      },
      workspaceMembers: {
        ...actual.api.workspaceMembers,
        list: vi.fn(),
      },
    },
  };
});

import { api } from '@/lib/api';
import { useWorkspaceVerification } from '../useWorkspaceVerification';

const getMeMock = vi.mocked(api.workspaces.getMe);
const listMembersMock = vi.mocked(api.workspaceMembers.list);

const UNVERIFIED_ME = {
  id: 'ws_1',
  name: 'Tech Brands Co.',
  slug: 'tech-brands-co',
  email: 'admin@techbrands.in',
  phone: null,
  industry: null,
  companySize: null,
  websiteUrl: null,
  logoUrl: null,
  verificationStatus: 'UNVERIFIED' as const,
};

function renderVerification() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  return renderHook(() => useWorkspaceVerification(), { wrapper });
}

describe('useWorkspaceVerification (F-0244)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    localStorage.setItem('brand_user_id', 'u_owner');
  });

  it('fails OPEN while the role query is still pending, even after `me` has resolved', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    // Never resolves within this test — simulates the role query still in flight.
    listMembersMock.mockImplementation(() => new Promise(() => {}));

    const { result } = renderVerification();

    // `me` settles first: status is known, but the role fetch is still pending.
    await waitFor(() => expect(result.current.status).toBe('UNVERIFIED'));

    expect(result.current.roleResolved).toBe(false);
    expect(result.current.roleError).toBe(false);
    expect(result.current.isLoading).toBe(true);
    // The bug: this used to be `false`, rendering the terminal "ask an admin" message to a
    // role that simply hadn't loaded yet.
    expect(result.current.canVerify).toBe(true);
  });

  it('fails OPEN when the role query rejects', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockRejectedValue(new Error('network error'));

    const { result } = renderVerification();

    await waitFor(() => expect(result.current.roleError).toBe(true));

    expect(result.current.roleResolved).toBe(false);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.canVerify).toBe(true);
  });

  it('fails OPEN when brand_user_id is missing from localStorage', async () => {
    localStorage.removeItem('brand_user_id');
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockResolvedValue([
      { id: 'm_1', workspaceId: 'ws_1', userId: 'u_owner', role: 'OWNER', active: true },
    ]);

    const { result } = renderVerification();

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.roleResolved).toBe(false);
    expect(result.current.roleError).toBe(true);
    expect(result.current.canVerify).toBe(true);
  });

  it('produces canVerify: false only once the role has genuinely resolved to a non-admin', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockResolvedValue([
      { id: 'm_1', workspaceId: 'ws_1', userId: 'u_owner', role: 'MEMBER', active: true },
    ]);

    const { result } = renderVerification();

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.roleResolved).toBe(true);
    expect(result.current.roleError).toBe(false);
    expect(result.current.canVerify).toBe(false);
  });

  it('produces canVerify: true once the role has resolved to OWNER', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockResolvedValue([
      { id: 'm_1', workspaceId: 'ws_1', userId: 'u_owner', role: 'OWNER', active: true },
    ]);

    const { result } = renderVerification();

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.roleResolved).toBe(true);
    expect(result.current.canVerify).toBe(true);
  });
});
