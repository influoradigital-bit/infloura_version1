/**
 * BrandFirstRunChecklist — the derivation rules, and the blast radius of its own failure.
 *
 * Two properties matter more than the layout:
 *
 *   1. **A step ticks only on proof.** Every `done` comes from live account state. While the
 *      pipeline or wallet is loading or has failed, the steps that read them report
 *      "undeterminable" — not "not done yet", which is a claim the data does not support.
 *
 *   2. **This widget can never take the dashboard down.** It is the only thing on the page that
 *      makes its own network call. If `api.campaigns` throws synchronously — renamed, absent,
 *      partially mocked — an unguarded call inside the effect white-screens the entire brand
 *      dashboard, and it does so on exactly the first-run account this component exists to help.
 *
 * Run: npx vitest run src/components/brand/dashboard/__tests__/BrandFirstRunChecklist.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const campaignsList = vi.fn();

vi.mock('@/lib/api', () => ({
  api: {
    get campaigns() {
      // A getter, so a test can make the *property access* itself throw — the real failure shape
      // when the client is missing, which a plain rejected promise does not reproduce.
      return campaignsListAccessor();
    },
  },
  ApiError: class ApiError extends Error {},
}));

let campaignsListAccessor: () => { list: typeof campaignsList } = () => ({ list: campaignsList });

import { BrandFirstRunChecklist } from '../BrandFirstRunChecklist';

const EMPTY_PIPELINE = [
  { stage: 'Outreach', count: 0 },
  { stage: 'Negotiating', count: 0 },
  { stage: 'Contracted', count: 0 },
  { stage: 'In Progress', count: 0 },
  { stage: 'Review', count: 0 },
  { stage: 'Settled', count: 0 },
];

function renderChecklist(props: Partial<React.ComponentProps<typeof BrandFirstRunChecklist>> = {}) {
  return render(
    <MemoryRouter>
      <BrandFirstRunChecklist
        pipeline={EMPTY_PIPELINE}
        pipelineReady
        escrowLocked={0}
        walletReady
        {...props}
      />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  campaignsList.mockReset();
  campaignsListAccessor = () => ({ list: campaignsList });
  campaignsList.mockResolvedValue({
    campaigns: [],
    meta: { page: 1, limit: 1, total: 0, hasMore: false },
  });
});

describe('BrandFirstRunChecklist — first-run account', () => {
  it('shows the full ladder with the campaign step active', async () => {
    renderChecklist();
    expect(await screen.findByText('Get your first campaign live')).toBeInTheDocument();
    expect(screen.getByText('Create your first campaign')).toBeInTheDocument();
    expect(screen.getByText('Create')).toBeInTheDocument(); // the active step's CTA
  });

  it('asks for only what it needs — a single campaign, not a page of them', async () => {
    renderChecklist();
    await waitFor(() => expect(campaignsList).toHaveBeenCalled());
    expect(campaignsList).toHaveBeenCalledWith({ page: 1, limit: 1 });
  });
});

describe('BrandFirstRunChecklist — derivation', () => {
  it('ticks the campaign step once a campaign exists, moving the CTA to the next step', async () => {
    campaignsList.mockResolvedValue({
      campaigns: [],
      meta: { page: 1, limit: 1, total: 1, hasMore: false },
    });
    renderChecklist();
    // "Discover" is step 2's CTA — its presence proves step 1 was counted as done.
    expect(await screen.findByText('Discover')).toBeInTheDocument();
  });

  it('counts a settled deal toward every earlier step rather than un-ticking them', async () => {
    campaignsList.mockResolvedValue({
      campaigns: [],
      meta: { page: 1, limit: 1, total: 1, hasMore: false },
    });
    const { container } = renderChecklist({
      pipeline: [...EMPTY_PIPELINE.slice(0, 5), { stage: 'Settled', count: 1 }],
    });
    // Every step proved done -> the ladder retires itself entirely.
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('treats escrow as proof the deal was funded even before the pipeline reflects it', async () => {
    renderChecklist({ escrowLocked: 50000 });
    // Step 4 is proved by escrow; the CTA therefore sits on an earlier unproved step, never on
    // "Contracts".
    expect(await screen.findByText('Create')).toBeInTheDocument();
    expect(screen.queryByText('Contracts')).toBeNull();
  });

  it('reports pipeline steps as undeterminable while the pipeline has not loaded', async () => {
    renderChecklist({ pipelineReady: false });
    expect(await screen.findByText(/couldn’t be checked/)).toBeInTheDocument();
  });
});

describe('BrandFirstRunChecklist — cannot take the dashboard down', () => {
  it('survives an api.campaigns that throws on property access', async () => {
    campaignsListAccessor = () => {
      throw new TypeError("Cannot read properties of undefined (reading 'list')");
    };
    renderChecklist();
    // Renders, and the campaign step stays undeterminable rather than crossed out.
    expect(await screen.findByText('Get your first campaign live')).toBeInTheDocument();
    expect(screen.getByText(/couldn’t be checked/)).toBeInTheDocument();
  });

  it('survives a rejected campaigns request without marking the step done', async () => {
    campaignsList.mockRejectedValue(new Error('network'));
    renderChecklist();
    expect(await screen.findByText('Get your first campaign live')).toBeInTheDocument();
    expect(screen.getByText('Create')).toBeInTheDocument();
  });
});
