/**
 * F-0279 (dead-recovery-api) — src/hooks/brand/useWorkspaceVerification.ts:88.
 *
 * F-0244 added `roleError` / `retryRole` to useWorkspaceVerification so a caller whose role
 * fetch is unresolved (pending, rejected, or no `brand_user_id` to match) has a way to tell
 * "unknown" apart from "confirmed non-admin", and a way to recover. The owner-lockout itself
 * was cured by `canVerify` failing OPEN — but nothing ever consumed `roleError`/`retryRole`, so
 * a genuine role-query failure produced no error surface and no recovery action anywhere.
 *
 * Decision (see .proof-os/gates/F-0279-role-recovery-consumed.sh header for the full record):
 * wire the recovery API into brand-verification.tsx — the actual KYC submission screen, the
 * highest-stakes surface for an unresolved role — rather than deleting it. campaign-form.tsx
 * and WorkspaceVerificationBanner.tsx are intentionally left on canVerify-only fail-open
 * behaviour, which is still correct on its own (canVerify derives from roleResolved, not from
 * roleError being surfaced), just without the extra retry affordance.
 *
 * This proves the RENDERED page, not the hook in isolation (useWorkspaceVerification.test.ts
 * already covers the hook's own fail-open logic): a rejected role query must show the amber
 * "couldn't confirm your role" banner with a working Retry button, and a successful role query
 * must show neither.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

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

// BrandKycForm pulls in react-hook-form/zod/file-upload plumbing unrelated to this record —
// stub it so the test stays focused on the roleError/retryRole affordance around it.
vi.mock('@/components/brand/BrandKycForm', () => ({
  BrandKycForm: () => <div data-testid="kyc-form-stub" />,
}));

import { api } from '@/lib/api';
import BrandVerificationPage from '../brand-verification';

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

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/settings/verification']}>
        <BrandVerificationPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BrandVerificationPage — role-recovery affordance (F-0279)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    localStorage.setItem('brand_user_id', 'u_owner');
  });

  it('role query rejects: shows the "couldn\'t confirm your role" banner with a Retry button, and still renders the KYC form', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockRejectedValue(new Error('network error'));

    renderPage();

    await waitFor(() =>
      expect(screen.getByText(/couldn.t confirm your workspace role/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    // Fail-open still applies — the form itself is not blocked by the unresolved role.
    expect(screen.getByTestId('kyc-form-stub')).toBeInTheDocument();
  });

  it('clicking Retry actually re-invokes the role query, not a no-op handler', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockRejectedValue(new Error('network error'));
    const user = userEvent.setup();

    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument());
    const callsBeforeRetry = listMembersMock.mock.calls.length;
    expect(callsBeforeRetry).toBeGreaterThan(0);

    await user.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() =>
      expect(listMembersMock.mock.calls.length).toBeGreaterThan(callsBeforeRetry),
    );
  });

  it('role query resolves cleanly: no error banner, no Retry button', async () => {
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockResolvedValue([
      { id: 'm_1', workspaceId: 'ws_1', userId: 'u_owner', role: 'OWNER', active: true },
    ]);

    renderPage();

    await waitFor(() => expect(screen.getByTestId('kyc-form-stub')).toBeInTheDocument());
    expect(screen.queryByText(/couldn.t confirm your workspace role/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('missing brand_user_id (unresolved role, same as roleError): still surfaces the recovery banner', async () => {
    localStorage.removeItem('brand_user_id');
    getMeMock.mockResolvedValue(UNVERIFIED_ME);
    listMembersMock.mockResolvedValue([
      { id: 'm_1', workspaceId: 'ws_1', userId: 'u_owner', role: 'OWNER', active: true },
    ]);

    renderPage();

    await waitFor(() =>
      expect(screen.getByText(/couldn.t confirm your workspace role/i)).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
