/**
 * F-0848 (T6, T8, T9, T10 + create-status handling) — the standard form's publish route.
 *
 * Before: "Publish Campaign" called `api.campaigns.create({ status: 'ACTIVE' })`, the one human
 * publish route that skipped both the secured-funds check and the platform fee. The server now
 * refuses any non-DRAFT create, so the form must: create as DRAFT → show the inline
 * "Secure the funds to publish" step → secure the funds (FundEscrowButton) → `update(id, ACTIVE)`.
 *
 * Every assertion drives the real controls with clicks and checks the API calls and their
 * arguments — a control that renders but does nothing fails here.
 *
 * Run: npx vitest run src/components/brand/campaigns/__tests__/campaign-form-f0848-publish-route.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CampaignForm, type CampaignFormData } from '../campaign-form';
import * as workspaceVerificationHook from '@/hooks/brand/useWorkspaceVerification';
import { ApiError } from '@/lib/api';

vi.mock('@/hooks/brand/useWorkspaceVerification');

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

const campaignsCreate = vi.fn();
const campaignsUpdate = vi.fn();
const campaignsGet = vi.fn();
const creatorsSuggestions = vi.fn();
const creatorsInvite = vi.fn();
const escrowList = vi.fn();
const fundEscrow = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isMoneyActionBlocked: () => false,
    api: {
      ...actual.api,
      campaigns: {
        ...actual.api.campaigns,
        create: (...a: unknown[]) => campaignsCreate(...a),
        update: (...a: unknown[]) => campaignsUpdate(...a),
        get: (...a: unknown[]) => campaignsGet(...a),
      },
      creators: {
        ...actual.api.creators,
        suggestions: (...a: unknown[]) => creatorsSuggestions(...a),
        invite: (...a: unknown[]) => creatorsInvite(...a),
      },
      wallet: { ...actual.api.wallet, escrowList: (...a: unknown[]) => escrowList(...a) },
    },
  };
});

vi.mock('@/lib/meera-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api');
  return {
    ...actual,
    meeraApi: { ...actual.meeraApi, fundEscrow: (...a: unknown[]) => fundEscrow(...a) },
  };
});

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

const FUNDED = { escrowHoldId: 'hold_1', amount: 25000, currency: 'INR', razorpayOrderId: null, status: 'FUNDED' };

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function renderForm(props: { campaignId?: string } = {}) {
  vi.mocked(workspaceVerificationHook.useWorkspaceVerification).mockReturnValue({
    status: 'VERIFIED',
    isLoading: false,
    isVerified: true,
    roleResolved: true,
    roleError: false,
    canVerify: true,
    retryRole: vi.fn(),
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const start = props.campaignId ? `/brand/campaigns/${props.campaignId}/edit` : '/brand/campaigns/new';
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[start]}>
        <Routes>
          <Route
            path="/brand/campaigns/new"
            element={<CampaignForm initialValues={VALID_INITIAL_VALUES} />}
          />
          <Route
            path="/brand/campaigns/:id/edit"
            element={<CampaignForm campaignId={props.campaignId} />}
          />
          <Route path="/brand/campaigns" element={<div>CAMPAIGNS LIST PAGE</div>} />
          <Route path="/brand/wallet" element={<div>WALLET PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function goToReview(user: ReturnType<typeof userEvent.setup>) {
  for (let i = 0; i < 4; i++) {
    await user.click(await screen.findByRole('button', { name: /^continue$/i }));
  }
  await screen.findByRole('button', { name: /publish campaign/i });
}

function statusesSentTo(mock: ReturnType<typeof vi.fn>): unknown[] {
  return mock.mock.calls.map((c) => (c[c.length - 1] as { status?: unknown } | undefined)?.status);
}

describe('CampaignForm — F-0848 publish route (save DRAFT → secure the funds → update ACTIVE)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    creatorsSuggestions.mockResolvedValue({ suggestions: [] });
    escrowList.mockResolvedValue([]);
  });

  it('T6: clicking "Publish Campaign" creates the campaign as DRAFT, never ACTIVE, and activates nothing yet', async () => {
    campaignsCreate.mockResolvedValue({ id: 'camp_new', status: 'DRAFT' });
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));

    await waitFor(() => expect(campaignsCreate).toHaveBeenCalledTimes(1));
    expect(campaignsCreate.mock.calls[0][0]).toMatchObject({ status: 'DRAFT', title: 'Diwali Collection Launch' });
    expect(statusesSentTo(campaignsCreate)).not.toContain('ACTIVE');

    // The step replaces the navigation; nothing is activated or funded without the brand's click.
    expect(await screen.findByRole('heading', { name: /secure the funds to publish/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /publish campaign/i })).not.toBeInTheDocument();
    expect(campaignsUpdate).not.toHaveBeenCalled();
    expect(fundEscrow).not.toHaveBeenCalled();
    expect(screen.queryByText('CAMPAIGNS LIST PAGE')).not.toBeInTheDocument();
  });

  it('T8: the new route still reaches a published campaign — fund the saved id, then update it to ACTIVE, then leave', async () => {
    campaignsCreate.mockResolvedValue({ id: 'camp_new', status: 'DRAFT' });
    fundEscrow.mockResolvedValue(FUNDED);
    campaignsUpdate.mockResolvedValue({ id: 'camp_new', status: 'ACTIVE' });
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));
    await user.click(await screen.findByRole('button', { name: /fund & go live/i }));

    await waitFor(() => expect(fundEscrow).toHaveBeenCalledTimes(1));
    expect(fundEscrow.mock.calls[0][0]).toBe('camp_new');
    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledWith('camp_new', { status: 'ACTIVE' }));
    expect(campaignsUpdate).toHaveBeenCalledTimes(1);
    // Funds first, activation second.
    expect(fundEscrow.mock.invocationCallOrder[0]).toBeLessThan(campaignsUpdate.mock.invocationCallOrder[0]);
    expect(await screen.findByText('CAMPAIGNS LIST PAGE')).toBeInTheDocument();
  });

  it('T9: a fee failure after funding keeps the brand on the step with a "Publish campaign" retry that never secures the funds again', async () => {
    campaignsCreate.mockResolvedValue({ id: 'camp_new', status: 'DRAFT' });
    fundEscrow.mockResolvedValue(FUNDED);
    campaignsUpdate.mockRejectedValueOnce(
      new ApiError(
        'INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH',
        'Insufficient wallet balance — top up Rs. 2500.00 to publish this campaign',
        402,
      ),
    );
    const retry = deferred<{ id: string; status: string }>();
    campaignsUpdate.mockReturnValueOnce(retry.promise);
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));
    await user.click(await screen.findByRole('button', { name: /fund & go live/i }));

    const step = await screen.findByTestId('secure-and-publish-step');
    const alert = await within(step).findByRole('alert');
    expect(alert).toHaveTextContent(/top up Rs\. 2500\.00 to publish this campaign/i);
    expect(alert).toHaveTextContent(/still a draft/i);
    // No funding control of any kind survives on the step — only the update retry and the exit.
    expect(within(step).getAllByRole('button').map((b) => b.textContent?.trim())).toEqual([
      'Publish campaign',
      'Keep as draft',
    ]);
    expect(screen.queryByText('CAMPAIGNS LIST PAGE')).not.toBeInTheDocument();

    // Double-click the retry: exactly one more update, and no second funding.
    await user.dblClick(within(step).getByRole('button', { name: /^publish campaign$/i }));
    expect(campaignsUpdate).toHaveBeenCalledTimes(2);
    expect(campaignsUpdate).toHaveBeenLastCalledWith('camp_new', { status: 'ACTIVE' });
    expect(fundEscrow).toHaveBeenCalledTimes(1);

    retry.resolve({ id: 'camp_new', status: 'ACTIVE' });
    expect(await screen.findByText('CAMPAIGNS LIST PAGE')).toBeInTheDocument();
    expect(campaignsUpdate).toHaveBeenCalledTimes(2);
    expect(fundEscrow).toHaveBeenCalledTimes(1);
  });

  it('a failed funds attempt shows a clear message, publishes nothing, and can be retried to a published campaign', async () => {
    campaignsCreate.mockResolvedValue({ id: 'camp_new', status: 'DRAFT' });
    fundEscrow
      .mockRejectedValueOnce(new ApiError('PAYMENTS_UNAVAILABLE', 'Payment collection is temporarily unavailable.', 503))
      .mockResolvedValueOnce(FUNDED);
    campaignsUpdate.mockResolvedValue({ id: 'camp_new', status: 'ACTIVE' });
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));
    await user.click(await screen.findByRole('button', { name: /fund & go live/i }));

    const step = await screen.findByTestId('secure-and-publish-step');
    expect(await within(step).findByRole('alert')).toHaveTextContent(/payment collection is temporarily unavailable/i);
    expect(within(step).getByText(/stays saved as a draft and you can try again/i)).toBeInTheDocument();
    expect(campaignsUpdate).not.toHaveBeenCalled();

    await user.click(within(step).getByRole('button', { name: /try again/i }));
    await user.click(await within(step).findByRole('button', { name: /fund & go live/i }));

    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledWith('camp_new', { status: 'ACTIVE' }));
    expect(fundEscrow).toHaveBeenCalledTimes(2);
    expect(campaignsUpdate).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('CAMPAIGNS LIST PAGE')).toBeInTheDocument();
  });

  it('a double-click on "Publish Campaign" creates exactly one draft', async () => {
    const create = deferred<{ id: string; status: string }>();
    campaignsCreate.mockReturnValue(create.promise);
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.dblClick(screen.getByRole('button', { name: /publish campaign/i }));
    create.resolve({ id: 'camp_new', status: 'DRAFT' });

    await screen.findByRole('heading', { name: /secure the funds to publish/i });
    expect(campaignsCreate).toHaveBeenCalledTimes(1);
  });

  it('T10: no text on the step says "escrow" — idle, and after a failed publish', async () => {
    campaignsCreate.mockResolvedValue({ id: 'camp_new', status: 'DRAFT' });
    fundEscrow.mockResolvedValue(FUNDED);
    campaignsUpdate.mockRejectedValue(
      new ApiError('ESCROW_NOT_FUNDED', 'Campaign has no escrow in FUNDED status — cannot activate', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));
    const step = await screen.findByTestId('secure-and-publish-step');
    expect(step.textContent).toMatch(/We hold ₹25,000 securely until creators deliver/);
    expect(step.textContent).not.toMatch(/escrow/i);

    await user.click(within(step).getByRole('button', { name: /fund & go live/i }));
    await within(step).findByRole('alert');
    expect(step.textContent).not.toMatch(/escrow/i);
  });

  it('CAMPAIGN_CREATE_STATUS_NOT_ALLOWED from the server gets a clear, specific message and no step', async () => {
    campaignsCreate.mockRejectedValue(
      new ApiError('CAMPAIGN_CREATE_STATUS_NOT_ALLOWED', 'A new campaign can only be created as DRAFT', 400),
    );
    const user = userEvent.setup({ delay: null });
    renderForm();

    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Your campaign was not saved',
          description: expect.stringMatching(/save the campaign, then secure the funds/i),
          variant: 'destructive',
        }),
      ),
    );
    expect(screen.queryByTestId('secure-and-publish-step')).not.toBeInTheDocument();
    // The brand can press Publish again — the control is still there and enabled.
    expect(screen.getByRole('button', { name: /publish campaign/i })).toBeEnabled();
  });

  it('a resumed draft whose funds are already secured is published with update only — never funded again', async () => {
    const CAMPAIGN_ID = 'camp_draft_funded';
    campaignsGet.mockResolvedValue({
      id: CAMPAIGN_ID,
      status: 'DRAFT',
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
    escrowList.mockResolvedValue([
      { escrowHoldId: 'h_other', status: 'FUNDED', amount: 1, currency: 'INR', campaignId: 'someone_else', milestoneId: null, fundedAt: null },
      { escrowHoldId: 'h_1', status: 'FUNDED', amount: 25000, currency: 'INR', campaignId: CAMPAIGN_ID, milestoneId: null, fundedAt: null },
    ]);
    campaignsUpdate.mockResolvedValue({ id: CAMPAIGN_ID, status: 'DRAFT' });
    const user = userEvent.setup({ delay: null });
    renderForm({ campaignId: CAMPAIGN_ID });

    await waitFor(() => expect(campaignsGet).toHaveBeenCalledWith(CAMPAIGN_ID));
    await goToReview(user);
    await user.click(screen.getByRole('button', { name: /publish campaign/i }));

    // The field save leaves the stored status alone — it never sends ACTIVE with the edit.
    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledTimes(1));
    expect(campaignsUpdate.mock.calls[0][1].status).toBeUndefined();

    const step = await screen.findByTestId('secure-and-publish-step');
    expect(await within(step).findByText(/funds secured for this campaign/i)).toBeInTheDocument();
    expect(within(step).queryByRole('button', { name: /fund & go live/i })).not.toBeInTheDocument();

    campaignsUpdate.mockResolvedValue({ id: CAMPAIGN_ID, status: 'ACTIVE' });
    await user.click(within(step).getByRole('button', { name: /^publish campaign$/i }));
    await waitFor(() => expect(campaignsUpdate).toHaveBeenLastCalledWith(CAMPAIGN_ID, { status: 'ACTIVE' }));
    expect(fundEscrow).not.toHaveBeenCalled();
    expect(await screen.findByText('CAMPAIGNS LIST PAGE')).toBeInTheDocument();
  });
});
