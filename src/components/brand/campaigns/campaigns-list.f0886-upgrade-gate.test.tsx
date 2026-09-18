/**
 * F-0886 (paywall-with-no-way-to-pay) — campaign templates.
 *
 * `POST /campaign-templates` is `@RequiresPlan CAMPAIGN_TEMPLATES`
 * (CampaignTemplateController.java:54), mirrored client-side by `Plan.campaignTemplatesEnabled`
 * (GET /billing/plan). Before this fix, "Save as template" always opened a plain name-entry
 * dialog regardless of plan — a Free workspace could only discover the block by typing a name
 * and reading a raw error toast. This pins two paths to the shared `UpgradeGate`, rendered INSIDE
 * the existing AlertDialog rather than a second modal:
 *   1. Proactive — `billingPlan.plan.campaignTemplatesEnabled === false` already tells us the
 *      workspace can't save templates; the gate renders the moment the dialog opens.
 *   2. Reactive — `api.campaignTemplates.create` 402s; the gate takes over from the form.
 *
 * Falsification: each "renders the gate" assertion was checked red by reverting
 * `campaignTemplatesGateActive` to `false` (form always shown) — see the run log in the task
 * report; reverted back to this file afterward, never via `git checkout`.
 *
 * Run: npx vitest run src/components/brand/campaigns/campaigns-list.f0886-upgrade-gate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { CampaignsList } from './campaigns-list';
import type { Campaign } from '@/lib/types';

const campaignsList = vi.fn();
const workspaceMembersList = vi.fn();
const campaignTemplatesCreate = vi.fn();
const initiateCheckoutMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      campaigns: { ...actual.api.campaigns, list: (...a: unknown[]) => campaignsList(...a) },
      workspaceMembers: {
        ...actual.api.workspaceMembers,
        list: (...a: unknown[]) => workspaceMembersList(...a),
      },
      campaignTemplates: {
        ...actual.api.campaignTemplates,
        create: (...a: unknown[]) => campaignTemplatesCreate(...a),
      },
      billing: {
        ...actual.api.billing,
        initiateCheckout: (...a: unknown[]) => initiateCheckoutMock(...a),
      },
    },
  };
});

function planWith(campaignTemplatesEnabled: boolean) {
  return {
    plan: {
      plan: {
        code: campaignTemplatesEnabled ? ('PRO' as const) : ('FREE' as const),
        name: campaignTemplatesEnabled ? 'Pro' : 'Free',
        priceInr: campaignTemplatesEnabled ? 499900 : 0,
        billingCycle: 'MONTHLY',
        feeBps: campaignTemplatesEnabled ? 700 : 1000,
        aiMonthlyAllotment: 150,
        seatLimit: 5,
        trackedCreatorLimit: null,
        creatorAnalyticsMonthlyLimit: null,
        exportEnabled: campaignTemplatesEnabled,
        campaignTemplatesEnabled,
      },
      subscription: { status: 'ACTIVE' as const, currentPeriodStart: null, currentPeriodEnd: null, cancelAtPeriodEnd: false },
    },
    usage: null,
    invoices: [],
    campaignInvoices: [],
    commissionInvoices: [],
    isLoading: false,
    error: null,
    refetch: () => {},
  };
}

let mockUseBillingReturn = planWith(false);
let mockCanManageBilling = true;

vi.mock('@/hooks/brand/useBilling', () => ({
  useBilling: () => mockUseBillingReturn,
}));
vi.mock('@/hooks/brand/useBrandBillingAccess', () => ({
  useBrandBillingAccess: () => ({ role: 'OWNER', canManage: mockCanManageBilling, isLoading: false }),
}));

function makeCampaign(id: string): Campaign {
  return {
    id,
    workspaceId: 'ws_1',
    title: `Campaign ${id}`,
    description: 'desc',
    objectives: [],
    status: 'DRAFT',
    budget: { min: 1000, max: 2000, currency: 'INR' },
    timeline: { startDate: new Date('2026-01-01'), endDate: new Date('2026-02-01') },
    platforms: ['INSTAGRAM'],
    contentTypes: ['POST'],
    isPrivate: false,
    maxCollaborators: 5,
    createdBy: 'user_1',
    createdAt: new Date('2026-01-01'),
    updatedAt: new Date('2026-01-01'),
  } as Campaign;
}

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/brand/campaigns']}>
      <CampaignsList />
    </MemoryRouter>,
  );
}

async function openSaveAsTemplate(cardTitle: string) {
  renderList();
  const user = userEvent.setup({ delay: null });
  const titleEl = await screen.findByText(cardTitle);
  const card = titleEl.closest('.group') as HTMLElement;
  const trigger = within(card).getByRole('button', { name: '' });
  await user.click(trigger);
  await user.click(await screen.findByRole('menuitem', { name: /save as template/i }));
  return user;
}

describe('CampaignsList — F-0886 campaign templates upgrade gate', () => {
  beforeEach(() => {
    [campaignsList, workspaceMembersList, campaignTemplatesCreate, initiateCheckoutMock].forEach((m) => m.mockReset());
    workspaceMembersList.mockResolvedValue([]);
    campaignsList.mockResolvedValue({
      campaigns: [makeCampaign('c1')],
      meta: { page: 1, limit: 100, total: 1, hasMore: false },
    });
    initiateCheckoutMock.mockResolvedValue({ checkoutUrl: 'https://razorpay.example/checkout' });
    mockUseBillingReturn = planWith(false);
    mockCanManageBilling = true;
  });

  it('renders the UpgradeGate instead of the name form when the plan has campaignTemplatesEnabled: false', async () => {
    await openSaveAsTemplate('Campaign c1');

    expect(await screen.findByTestId('upgrade-gate')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('e.g. Diwali creator drop')).not.toBeInTheDocument();
  });

  it('renders the normal name form when the plan has campaign templates enabled', async () => {
    mockUseBillingReturn = planWith(true);
    await openSaveAsTemplate('Campaign c1');

    await screen.findByText('Save as template');
    expect(screen.queryByTestId('upgrade-gate')).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText('e.g. Diwali creator drop')).toBeInTheDocument();
  });

  it('the CTA on the gate reaches real checkout', async () => {
    Object.defineProperty(window, 'location', { value: { ...window.location, assign: vi.fn() }, writable: true });
    const user = await openSaveAsTemplate('Campaign c1');
    await screen.findByTestId('upgrade-gate');

    await user.click(screen.getByRole('button', { name: /upgrade to pro/i }));
    await waitFor(() => expect(initiateCheckoutMock).toHaveBeenCalledWith('PRO'));
    await waitFor(() => expect(window.location.assign).toHaveBeenCalledWith('https://razorpay.example/checkout'));
  });

  it('a MANAGER/MEMBER/VIEWER sees why they cannot upgrade, not a dead CTA', async () => {
    mockCanManageBilling = false;
    await openSaveAsTemplate('Campaign c1');
    await screen.findByTestId('upgrade-gate');

    expect(screen.queryByRole('button', { name: /upgrade to pro/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Only a workspace owner or admin can upgrade the plan/i)).toBeInTheDocument();
  });

  it('falls back to the UpgradeGate when the save request itself 402s', async () => {
    mockUseBillingReturn = planWith(true); // plan says enabled; server disagrees at request time
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    campaignTemplatesCreate.mockRejectedValue(new ApiError('UPGRADE_REQUIRED', 'Upgrade to Pro to save templates', 402));
    const user = await openSaveAsTemplate('Campaign c1');

    await user.type(await screen.findByPlaceholderText('e.g. Diwali creator drop'), 'Diwali drop');
    await user.click(screen.getByRole('button', { name: /save template/i }));

    expect(await screen.findByTestId('upgrade-gate')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('e.g. Diwali creator drop')).not.toBeInTheDocument();
  });
});
