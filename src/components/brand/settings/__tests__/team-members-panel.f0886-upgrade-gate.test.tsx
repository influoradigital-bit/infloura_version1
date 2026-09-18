/**
 * F-0886 (paywall-with-no-way-to-pay) — team invites / seats.
 *
 * Seats are Pro-gated (`Plan.seatLimit`, GET /billing/plan). Before this fix the invite form was
 * always rendered regardless of plan, so a Free workspace at its seat limit could only discover
 * the block by submitting the form and reading a raw error. This pins two paths to the same
 * shared `UpgradeGate`:
 *   1. Proactive — `billingPlan.plan.seatLimit` already tells us the workspace is full; the gate
 *      renders instead of the form, no wasted request.
 *   2. Reactive — `api.workspaceMembers.invite` 402s (a seat filled by someone else in the same
 *      moment); the gate takes over from the form.
 * Plus the role-aware branch: a MANAGER never sees the seat gate at all (the existing role gate
 * already hides the whole invite form from them), and a viewer of the gate itself sees the
 * upgrade CTA only when they can manage billing.
 *
 * Falsification: each "renders the gate" assertion was checked red by reverting the
 * `seatLimitReached || upgradeRequired` condition to `false` (form always shown) — see the run
 * log in the task report; reverted back to this file afterward, never via `git checkout`.
 *
 * Run: npx vitest run src/components/brand/settings/__tests__/team-members-panel.f0886-upgrade-gate.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TeamMembersPanel } from '../team-members-panel';
import { toast } from '@/hooks/use-toast';
import type { UseBillingResult } from '@/hooks/brand/useBilling';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

const listMock = vi.fn();
const listInvitesMock = vi.fn();
const inviteMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      workspaceMembers: {
        ...actual.api.workspaceMembers,
        list: (...a: unknown[]) => listMock(...a),
        listInvites: (...a: unknown[]) => listInvitesMock(...a),
        invite: (...a: unknown[]) => inviteMock(...a),
      },
      billing: {
        ...actual.api.billing,
        initiateCheckout: vi.fn().mockResolvedValue({ checkoutUrl: 'https://razorpay.example/checkout' }),
      },
    },
  };
});

function freePlan(): UseBillingResult {
  return {
    plan: {
      plan: {
        code: 'FREE' as const,
        name: 'Free',
        priceInr: 0,
        billingCycle: 'MONTHLY',
        feeBps: 1000,
        aiMonthlyAllotment: 150,
        seatLimit: 1,
        trackedCreatorLimit: 5,
        creatorAnalyticsMonthlyLimit: 1,
        exportEnabled: false,
        campaignTemplatesEnabled: false,
      },
      subscription: {
        status: 'ACTIVE' as const,
        currentPeriodStart: null,
        currentPeriodEnd: null,
        cancelAtPeriodEnd: false,
      },
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

function proPlan(seatLimit: number): UseBillingResult {
  const base = freePlan();
  return {
    ...base,
    plan: {
      plan: { ...base.plan!.plan, code: 'PRO', seatLimit },
      subscription: base.plan!.subscription,
    },
  };
}

let mockUseBillingReturn = freePlan();
let mockCanManageBilling = true;

vi.mock('@/hooks/brand/useBilling', () => ({
  useBilling: () => mockUseBillingReturn,
}));
vi.mock('@/hooks/brand/useBrandBillingAccess', () => ({
  useBrandBillingAccess: () => ({ role: 'OWNER', canManage: mockCanManageBilling, isLoading: false }),
}));

const OWNER = { id: 'm_owner', workspaceId: 'ws_1', userId: 'u_owner', role: 'OWNER', active: true };

describe('TeamMembersPanel — F-0886 seat upgrade gate', () => {
  beforeEach(() => {
    [listMock, listInvitesMock, inviteMock].forEach((m) => m.mockReset());
    vi.mocked(toast).mockClear();
    localStorage.setItem('brand_user_id', 'u_owner');
    listMock.mockResolvedValue([OWNER]); // 1 active member — Free plan's seatLimit is 1
    listInvitesMock.mockResolvedValue([]);
    mockUseBillingReturn = freePlan();
    mockCanManageBilling = true;
  });

  it('renders the UpgradeGate instead of the invite form when the workspace is at its seat limit', async () => {
    render(<TeamMembersPanel />);
    await screen.findByText('Team members');

    expect(screen.getByTestId('upgrade-gate')).toBeInTheDocument();
    expect(screen.queryByLabelText('Work email')).not.toBeInTheDocument();
    expect(screen.getByText(/Your plan allows 1 seat\. Upgrade to Pro/i)).toBeInTheDocument();
  });

  it('renders the normal invite form when the plan has room for more seats', async () => {
    mockUseBillingReturn = proPlan(5);
    render(<TeamMembersPanel />);
    await screen.findByText('Team members');

    expect(screen.queryByTestId('upgrade-gate')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Work email')).toBeInTheDocument();
  });

  it('the CTA on the gate reaches real checkout', async () => {
    const user = userEvent.setup({ delay: null });
    Object.defineProperty(window, 'location', { value: { ...window.location, assign: vi.fn() }, writable: true });
    render(<TeamMembersPanel />);
    await screen.findByTestId('upgrade-gate');

    await user.click(screen.getByRole('button', { name: /upgrade to pro/i }));
    await waitFor(() => expect(window.location.assign).toHaveBeenCalledWith('https://razorpay.example/checkout'));
  });

  it('a MANAGER/MEMBER/VIEWER sees why they cannot upgrade, not a dead CTA', async () => {
    mockCanManageBilling = false;
    render(<TeamMembersPanel />);
    await screen.findByTestId('upgrade-gate');

    expect(screen.queryByRole('button', { name: /upgrade to pro/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Only a workspace owner or admin can upgrade the plan/i)).toBeInTheDocument();
  });

  it('falls back to the UpgradeGate when the invite request itself 402s (seat filled concurrently)', async () => {
    // Room for one more seat per the plan (2 active members would still be under a limit of 5),
    // so the form renders first and only the live server 402 flips it to the gate.
    mockUseBillingReturn = proPlan(5);
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    inviteMock.mockRejectedValue(new ApiError('UPGRADE_REQUIRED', 'Upgrade to Pro to add more seats', 402));
    const user = userEvent.setup({ delay: null });
    render(<TeamMembersPanel />);
    await screen.findByLabelText('Work email');

    await user.type(screen.getByLabelText('Work email'), 'newhire@company.com');
    await user.click(screen.getByRole('button', { name: /send invite/i }));

    expect(await screen.findByTestId('upgrade-gate')).toBeInTheDocument();
    expect(screen.queryByLabelText('Work email')).not.toBeInTheDocument();
  });
});
