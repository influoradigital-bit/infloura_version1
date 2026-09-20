/**
 * F-0848 (T7 + the Hype half of T8/T9) — the Hype page's Launch route.
 *
 * Before: Launch called `api.campaigns.create({ status: 'ACTIVE' })`, skipping the secured-funds
 * check and the platform fee. Now Launch creates a DRAFT → the inline "Secure the funds to
 * publish" step → FundEscrowButton → `update(id, { status: 'ACTIVE' })`.
 *
 * Drives the real controls (typing, the Radix Select option click, the Launch and fund buttons)
 * and asserts the API calls and their arguments.
 *
 * Run: npx vitest run src/pages/brand-new-hype-campaign.f0848-publish-route.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BrandNewHypeCampaignPage from './brand-new-hype-campaign';
import * as workspaceVerificationHook from '@/hooks/brand/useWorkspaceVerification';
import { ApiError } from '@/lib/api';

vi.mock('@/hooks/brand/useWorkspaceVerification');

const toastMock = vi.fn();
vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
}));

const { campaignsCreate, campaignsUpdate, campaignsGet, escrowList, fundEscrow } = vi.hoisted(() => ({
  campaignsCreate: vi.fn(),
  campaignsUpdate: vi.fn(),
  campaignsGet: vi.fn(),
  escrowList: vi.fn(),
  fundEscrow: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isMoneyActionBlocked: () => false,
    api: {
      ...actual.api,
      campaigns: { ...actual.api.campaigns, create: campaignsCreate, update: campaignsUpdate, get: campaignsGet },
      wallet: { ...actual.api.wallet, escrowList },
    },
  };
});

vi.mock('@/lib/meera-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/meera-api')>('@/lib/meera-api');
  return { ...actual, meeraApi: { ...actual.meeraApi, fundEscrow } };
});

const FUNDED = { escrowHoldId: 'hold_1', amount: 35000, currency: 'INR', razorpayOrderId: null, status: 'FUNDED' };

function renderHypePage(verified = true) {
  vi.mocked(workspaceVerificationHook.useWorkspaceVerification).mockReturnValue({
    status: null,
    isLoading: false,
    isVerified: verified,
    roleResolved: true,
    roleError: false,
    canVerify: true,
    retryRole: vi.fn(),
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/campaigns/new/hype']}>
        <Routes>
          <Route path="/brand/campaigns/new/hype" element={<BrandNewHypeCampaignPage />} />
          <Route path="/brand/campaigns" element={<div>CAMPAIGNS LIST PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Edit/resume mode: `BrandNewHypeCampaignPage` takes `campaignId` as a prop (mounted by
 * `BrandEditCampaignPage` — see the file's own javadoc), not a route param.
 */
function renderResumedHypePage(campaignId: string, verified = true) {
  vi.mocked(workspaceVerificationHook.useWorkspaceVerification).mockReturnValue({
    status: null,
    isLoading: false,
    isVerified: verified,
    roleResolved: true,
    roleError: false,
    canVerify: true,
    retryRole: vi.fn(),
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/campaigns/hype/edit']}>
        <Routes>
          <Route path="/brand/campaigns/hype/edit" element={<BrandNewHypeCampaignPage campaignId={campaignId} />} />
          <Route path="/brand/campaigns" element={<div>CAMPAIGNS LIST PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  fireEvent.change(screen.getByLabelText(/campaign title/i), { target: { value: 'Glow Drop Challenge' } });
  fireEvent.change(screen.getByLabelText(/source reel url/i), { target: { value: 'https://instagram.com/reel/abc123' } });
  fireEvent.change(screen.getByLabelText(/campaign hashtag/i), { target: { value: 'GlowDrop' } });
  fireEvent.change(screen.getByLabelText(/per-reel rate/i), { target: { value: '3500' } });
  fireEvent.change(screen.getByLabelText(/slot cap/i), { target: { value: '10' } });
  fireEvent.change(screen.getByLabelText(/end brand name/i), { target: { value: 'Kavala Skincare' } });
  // Radix Select in jsdom: click the option — {ArrowDown}{Enter} leaves the listbox open.
  await user.click(screen.getByRole('combobox', { name: /end brand category/i }));
  await user.click(await screen.findByRole('option', { name: 'Beauty' }));
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('BrandNewHypeCampaignPage — F-0848 publish route', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    escrowList.mockResolvedValue([]);
  });

  it('T7: clicking "Launch Hype Campaign" creates the campaign as DRAFT, never ACTIVE', async () => {
    campaignsCreate.mockResolvedValue({ id: 'hype_new', status: 'DRAFT' });
    const user = userEvent.setup();
    renderHypePage();
    await fillValidForm(user);

    await user.click(screen.getByRole('button', { name: /launch hype campaign/i }));

    await waitFor(() => expect(campaignsCreate).toHaveBeenCalledTimes(1));
    expect(campaignsCreate.mock.calls[0][0]).toMatchObject({ status: 'DRAFT', campaignType: 'HYPE' });
    expect(campaignsCreate.mock.calls.map((c) => c[0].status)).not.toContain('ACTIVE');
    expect(await screen.findByRole('heading', { name: /secure the funds to publish/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /launch hype campaign/i })).not.toBeInTheDocument();
    expect(campaignsUpdate).not.toHaveBeenCalled();
    expect(screen.queryByText('CAMPAIGNS LIST PAGE')).not.toBeInTheDocument();
  });

  it('T8 (Hype): funds are secured for the saved id, then update sets ACTIVE, then the brand lands on the list', async () => {
    campaignsCreate.mockResolvedValue({ id: 'hype_new', status: 'DRAFT' });
    fundEscrow.mockResolvedValue(FUNDED);
    campaignsUpdate.mockResolvedValue({ id: 'hype_new', status: 'ACTIVE' });
    const user = userEvent.setup();
    renderHypePage();
    await fillValidForm(user);

    await user.click(screen.getByRole('button', { name: /launch hype campaign/i }));
    const step = await screen.findByTestId('secure-and-publish-step');
    expect(step.textContent).toMatch(/We hold ₹35,000 securely until creators deliver/);
    expect(step.textContent).not.toMatch(/escrow/i);
    await user.click(within(step).getByRole('button', { name: /fund & go live/i }));

    await waitFor(() => expect(campaignsUpdate).toHaveBeenCalledWith('hype_new', { status: 'ACTIVE' }));
    expect(fundEscrow).toHaveBeenCalledTimes(1);
    expect(fundEscrow.mock.calls[0][0]).toBe('hype_new');
    expect(fundEscrow.mock.invocationCallOrder[0]).toBeLessThan(campaignsUpdate.mock.invocationCallOrder[0]);
    expect(await screen.findByText('CAMPAIGNS LIST PAGE')).toBeInTheDocument();
  });

  it('T9 (Hype): a failed publish after funding offers "Publish campaign" and never secures the funds again', async () => {
    campaignsCreate.mockResolvedValue({ id: 'hype_new', status: 'DRAFT' });
    fundEscrow.mockResolvedValue(FUNDED);
    campaignsUpdate
      .mockRejectedValueOnce(
        new ApiError('INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH', 'Insufficient wallet balance — top up Rs. 3500.00 to publish this campaign', 402),
      )
      .mockResolvedValueOnce({ id: 'hype_new', status: 'ACTIVE' });
    const user = userEvent.setup();
    renderHypePage();
    await fillValidForm(user);

    await user.click(screen.getByRole('button', { name: /launch hype campaign/i }));
    const step = await screen.findByTestId('secure-and-publish-step');
    await user.click(within(step).getByRole('button', { name: /fund & go live/i }));

    expect(await within(step).findByRole('alert')).toHaveTextContent(/top up Rs\. 3500\.00/i);
    await user.click(within(step).getByRole('button', { name: /^publish campaign$/i }));

    expect(await screen.findByText('CAMPAIGNS LIST PAGE')).toBeInTheDocument();
    expect(campaignsUpdate).toHaveBeenCalledTimes(2);
    expect(fundEscrow).toHaveBeenCalledTimes(1);
  });

  it('a repeated submit (Enter held / double submit) creates exactly one draft', async () => {
    const create = deferred<{ id: string; status: string }>();
    campaignsCreate.mockReturnValue(create.promise);
    const user = userEvent.setup();
    const { container } = renderHypePage();
    await fillValidForm(user);

    const form = container.querySelector('form')!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    create.resolve({ id: 'hype_new', status: 'DRAFT' });
    await screen.findByTestId('secure-and-publish-step');
    fireEvent.submit(form);

    expect(campaignsCreate).toHaveBeenCalledTimes(1);
  });

  it('a KNOWN-unverified workspace is stopped before anything is saved or funded', async () => {
    const user = userEvent.setup();
    renderHypePage(false);
    await fillValidForm(user);

    await user.click(screen.getByRole('button', { name: /launch hype campaign/i }));

    expect(await screen.findByRole('button', { name: /save as draft|save draft/i })).toBeInTheDocument();
    expect(campaignsCreate).not.toHaveBeenCalled();
    expect(screen.queryByTestId('secure-and-publish-step')).not.toBeInTheDocument();
  });

  it('CAMPAIGN_CREATE_STATUS_NOT_ALLOWED gets a clear, specific message', async () => {
    campaignsCreate.mockRejectedValue(
      new ApiError('CAMPAIGN_CREATE_STATUS_NOT_ALLOWED', 'A new campaign can only be created as DRAFT', 400),
    );
    const user = userEvent.setup();
    renderHypePage();
    await fillValidForm(user);

    await user.click(screen.getByRole('button', { name: /launch hype campaign/i }));

    await waitFor(() =>
      expect(toastMock).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Your campaign was not saved', variant: 'destructive' }),
      ),
    );
    expect(screen.queryByTestId('secure-and-publish-step')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /launch hype campaign/i })).toBeEnabled();
  });

  // F-0871 — mirrors campaign-form-f0848-publish-route.test.tsx's equivalent test. Reopening a
  // HYPE draft whose funds are ALREADY secured must look for the existing FUNDED hold
  // (`checkExistingFunds`) before ever showing the fund control again — otherwise the brand is
  // offered funding a second time for money already secured. A mutation that hardcodes
  // `checkExistingFunds: false` (dropping the `isEditing` lookup) makes this test go red; every
  // other test in this file stays green against that same mutation, which is why this needed its
  // own case.
  it('F-0871: a resumed HYPE draft whose funds are already secured is published with update only — never funded again', async () => {
    const CAMPAIGN_ID = 'hype_draft_funded';
    campaignsGet.mockResolvedValue({
      id: CAMPAIGN_ID,
      status: 'DRAFT',
      title: 'Glow Drop Challenge',
      hype: {
        sourceReelUrl: 'https://instagram.com/reel/abc123',
        audioTrack: '',
        hashtag: '#GlowDrop',
        formatLanes: ['Remix the hook'],
        perReelRate: 3500,
        slotCap: 10,
        slotsFilled: 0,
      },
      endBrandName: 'Kavala Skincare',
      endBrandCategory: 'Beauty',
    });
    escrowList.mockResolvedValue([
      { escrowHoldId: 'h_other', status: 'FUNDED', amount: 1, currency: 'INR', campaignId: 'someone_else', milestoneId: null, fundedAt: null },
      { escrowHoldId: 'h_1', status: 'FUNDED', amount: 35000, currency: 'INR', campaignId: CAMPAIGN_ID, milestoneId: null, fundedAt: null },
    ]);
    campaignsUpdate.mockResolvedValue({ id: CAMPAIGN_ID, status: 'DRAFT' });
    const user = userEvent.setup();
    renderResumedHypePage(CAMPAIGN_ID);

    await waitFor(() => expect(campaignsGet).toHaveBeenCalledWith(CAMPAIGN_ID));
    await user.click(await screen.findByRole('button', { name: /review.*launch/i }));

    // Resuming an edit leaves the stored status alone — it never sends ACTIVE with the field save.
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
