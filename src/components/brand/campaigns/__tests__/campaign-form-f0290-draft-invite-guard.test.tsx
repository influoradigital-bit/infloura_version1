/**
 * T-CREATORCONNECT-0902 Q6.2 (High) regression pin.
 *
 * Before the fix, `handleSubmit` fired the Discover handoff invite (`api.creators.invite`) on
 * every create regardless of status, so saving a DRAFT silently invited the creator to a campaign
 * the brand had not published yet — and (per CreatorDiscoveryService's own DRAFT guard) made that
 * draft undeletable. The fix gates the invite on `status === 'ACTIVE'`; a DRAFT save instead
 * stashes the handoff in localStorage (keyed by the new campaign id) so the invite fires later
 * when this same draft is actually published.
 *
 * Drives the real multi-step wizard via `initialValues` prefilled with everything `validateStep`
 * requires, so Continue/Publish are reachable without hand-filling every field (the codebase's own
 * precedent — campaign-form-f0280-simple.test.tsx — notes this is the reason the F-0280 pin settled
 * for logic-only assertions instead; prefilling via `initialValues` sidesteps that here).
 *
 * Run: npx vitest run src/components/brand/campaigns/__tests__/campaign-form-f0290-draft-invite-guard.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CampaignForm, type CampaignFormData } from '../campaign-form';
import * as workspaceVerificationHook from '@/hooks/brand/useWorkspaceVerification';

vi.mock('@/hooks/brand/useWorkspaceVerification');

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

const campaignsCreate = vi.fn();
const campaignsUpdate = vi.fn();
const campaignsGet = vi.fn();
const creatorsInvite = vi.fn();
const creatorsSuggestions = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      campaigns: { ...actual.api.campaigns, create: (...a: unknown[]) => campaignsCreate(...a), update: (...a: unknown[]) => campaignsUpdate(...a), get: (...a: unknown[]) => campaignsGet(...a) },
      creators: { ...actual.api.creators, invite: (...a: unknown[]) => creatorsInvite(...a), suggestions: (...a: unknown[]) => creatorsSuggestions(...a) },
    },
  };
});

const PENDING_INVITE_KEY = (campaignId: string) => `influora:pending-creator-invite:${campaignId}`;

// Satisfies every field validateStep checks across basics/content/budget, so the wizard's four
// "Continue" clicks (and the eventual submit) never hit a validation error.
const VALID_INITIAL_VALUES: Partial<CampaignFormData> = {
  title: 'Diwali Collection Launch',
  description: 'A festive campaign for our new collection.',
  objectives: ['Brand Awareness'],
  endBrandName: 'Acme Fashion',
  endBrandCategory: 'Fashion',
  platforms: ['INSTAGRAM'] as CampaignFormData['platforms'],
  contentTypes: ['REEL'] as CampaignFormData['contentTypes'],
  startDate: new Date('2026-10-01'),
  endDate: new Date('2026-10-31'),
  budgetMin: 5000,
  budgetMax: 25000,
};

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

function mockVerified() {
  vi.mocked(workspaceVerificationHook.useWorkspaceVerification).mockReturnValue({
    status: 'VERIFIED',
    isLoading: false,
    isVerified: true,
    roleResolved: true,
    roleError: false,
    canVerify: true,
    retryRole: vi.fn(),
  });
}

async function goToReview(user: ReturnType<typeof userEvent.setup>) {
  for (let i = 0; i < 4; i++) {
    await user.click(await screen.findByRole('button', { name: /^continue$/i }));
  }
  await screen.findByRole('button', { name: /save draft/i });
}

describe('CampaignForm — DRAFT save must not invite the Discover handoff creator (Q6.2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mockVerified();
    creatorsSuggestions.mockResolvedValue({ suggestions: [] });
  });

  it('handleSubmit(DRAFT): never calls api.creators.invite, and stashes the handoff instead', async () => {
    campaignsCreate.mockResolvedValue({ id: 'campaign_draft_1', status: 'DRAFT' });

    const user = userEvent.setup({ delay: null });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/brand/campaigns/new?creatorId=cp_01HLINKED&ig=foodie.mumbai']}>
          <CampaignForm initialValues={VALID_INITIAL_VALUES} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /save draft/i }));

    await waitFor(() => expect(campaignsCreate).toHaveBeenCalledTimes(1));
    expect(creatorsInvite).not.toHaveBeenCalled();

    const stashed = localStorage.getItem(PENDING_INVITE_KEY('campaign_draft_1'));
    expect(stashed).not.toBeNull();
    expect(JSON.parse(stashed!)).toMatchObject({ creatorId: 'cp_01HLINKED', ig: 'foodie.mumbai' });
  });

  it('a later publish of that same (now-edited) draft consumes the stash exactly once', async () => {
    const CAMPAIGN_ID = 'campaign_draft_2';
    // Seed the stash exactly as a prior DRAFT save (the test above) would have left it — this
    // test only needs to prove the CONSUME half, independent of the STASH half above.
    localStorage.setItem(
      PENDING_INVITE_KEY(CAMPAIGN_ID),
      JSON.stringify({ creatorId: 'cp_01HLINKED', ig: 'foodie.mumbai' }),
    );
    campaignsGet.mockResolvedValue({
      id: CAMPAIGN_ID,
      title: 'Diwali Collection Launch',
      description: 'A festive campaign for our new collection.',
      objectives: ['Brand Awareness'],
      platforms: ['INSTAGRAM'],
      contentTypes: ['REEL'],
      budget: { min: 5000, max: 25000, currency: 'INR' },
      timeline: { startDate: '2026-10-01', endDate: '2026-10-31' },
      maxCollaborators: 10,
      requirements: [],
      hashtags: [],
      brandGuidelines: '',
      isPrivate: false,
      targetAudience: { interests: [] },
      endBrandName: 'Acme Fashion',
      endBrandCategory: 'Fashion',
    });
    campaignsUpdate.mockResolvedValue({ id: CAMPAIGN_ID, status: 'ACTIVE' });
    creatorsInvite.mockResolvedValue({ id: 'collab_1', status: 'INVITED', appliedAt: new Date().toISOString() });

    const user = userEvent.setup({ delay: null });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/brand/campaigns/${CAMPAIGN_ID}/edit`]}>
          <CampaignForm campaignId={CAMPAIGN_ID} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(campaignsGet).toHaveBeenCalledWith(CAMPAIGN_ID));
    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));

    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(creatorsInvite).toHaveBeenCalledTimes(1));
    expect(creatorsInvite).toHaveBeenCalledWith('cp_01HLINKED', CAMPAIGN_ID);

    // Consumed at most once — the key must be gone so a second publish (e.g. re-editing an
    // already-ACTIVE campaign later) can never re-fire the same invite.
    expect(localStorage.getItem(PENDING_INVITE_KEY(CAMPAIGN_ID))).toBeNull();
  });
});
