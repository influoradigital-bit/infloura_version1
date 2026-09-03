/**
 * T-CREATORCONNECT-0902 Q6.4 (High) regression pin — the Hype page's own honest banner.
 *
 * Pins that arriving at `/brand/campaigns/new/hype?creatorId=...&ig=...` (the handoff
 * brand-new-campaign.tsx now forwards — see brand-new-campaign.hype-handoff.test.tsx) renders
 * `data-testid="hype-handoff-banner"` explaining Hype cannot invite a specific creator, using the
 * forwarded `@igUsername` — never silently drops the handoff (the original Q6.4 bug) and never
 * fakes an invite Hype's flat-rate/slots model does not support.
 *
 * Run: npx vitest run src/pages/brand-new-hype-campaign.handoff-banner.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BrandNewHypeCampaignPage from './brand-new-hype-campaign';
import * as workspaceVerificationHook from '@/hooks/brand/useWorkspaceVerification';

vi.mock('@/hooks/brand/useWorkspaceVerification');

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: { ...actual.api, campaigns: { ...actual.api.campaigns, get: vi.fn(), create: vi.fn() } },
  };
});

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function renderHypePage(initialEntry: string) {
  vi.mocked(workspaceVerificationHook.useWorkspaceVerification).mockReturnValue({
    status: null,
    isLoading: false,
    isVerified: true,
    roleResolved: true,
    roleError: false,
    canVerify: true,
    retryRole: vi.fn(),
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <BrandNewHypeCampaignPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BrandNewHypeCampaignPage — handoff banner (Q6.4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the honest "cannot invite" banner using the forwarded @igUsername', async () => {
    renderHypePage('/brand/campaigns/new/hype?creatorId=cp_01HLINKED&ig=foodie.mumbai');

    const banner = await screen.findByTestId('hype-handoff-banner');
    expect(banner).toHaveTextContent(/hype campaigns don.t support inviting/i);
    expect(banner).toHaveTextContent('@foodie.mumbai');
  });

  it('falls back to "this creator" when only creatorId (no ig) was forwarded', async () => {
    renderHypePage('/brand/campaigns/new/hype?creatorId=cp_01HLINKED');

    const banner = await screen.findByTestId('hype-handoff-banner');
    expect(banner).toHaveTextContent(/inviting this creator directly/i);
  });

  it('renders no banner at all when there was no handoff', async () => {
    renderHypePage('/brand/campaigns/new/hype');

    expect(screen.queryByTestId('hype-handoff-banner')).not.toBeInTheDocument();
  });
});
