/**
 * F-0886 — UpgradeGate, the shared component every Pro-gated brand surface renders instead of
 * silently refusing the action. Pins: default copy per feature, the CTA reaching real checkout
 * (`api.billing.initiateCheckout('PRO')` + a redirect), and the role-aware branch that explains
 * rather than offers a dead button to a MANAGER/MEMBER/VIEWER.
 *
 * Run: npx vitest run src/components/brand/billing/UpgradeGate.test.tsx
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { UpgradeGate } from './UpgradeGate';
import { toast } from '@/hooks/use-toast';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

const initiateCheckoutMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      billing: {
        ...actual.api.billing,
        initiateCheckout: (...a: unknown[]) => initiateCheckoutMock(...a),
      },
    },
  };
});

describe('UpgradeGate — F-0886', () => {
  const originalAssign = window.location.assign;

  beforeEach(() => {
    initiateCheckoutMock.mockReset();
    vi.mocked(toast).mockClear();
    initiateCheckoutMock.mockResolvedValue({ checkoutUrl: 'https://razorpay.example/checkout/abc' });
    // jsdom doesn't implement navigation; stub assign so we can assert on it.
    Object.defineProperty(window, 'location', {
      value: { ...window.location, assign: vi.fn() },
      writable: true,
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { value: { ...window.location, assign: originalAssign }, writable: true });
  });

  it('renders the default copy for the given feature', () => {
    render(<UpgradeGate feature="team invites" canManageBilling />);
    expect(screen.getByText(/Pro is needed to invite more teammates/i)).toBeInTheDocument();
  });

  it('prefers a call-site reason over the default copy when given', () => {
    render(
      <UpgradeGate
        feature="team invites"
        reason="Your plan allows 1 seat. Upgrade to Pro to invite more teammates."
        canManageBilling
      />,
    );
    expect(screen.getByText('Your plan allows 1 seat. Upgrade to Pro to invite more teammates.')).toBeInTheDocument();
    expect(screen.queryByText(/Pro is needed to invite more teammates to this workspace\./)).not.toBeInTheDocument();
  });

  it('an OWNER/ADMIN sees a working Upgrade CTA that starts real checkout and redirects', async () => {
    const user = userEvent.setup({ delay: null });
    render(<UpgradeGate feature="analytics" canManageBilling />);

    const cta = screen.getByRole('button', { name: /upgrade to pro/i });
    expect(cta).toBeEnabled();
    await user.click(cta);

    await waitFor(() => expect(initiateCheckoutMock).toHaveBeenCalledWith('PRO'));
    await waitFor(() => expect(window.location.assign).toHaveBeenCalledWith('https://razorpay.example/checkout/abc'));
  });

  it('a MANAGER/MEMBER/VIEWER sees why they cannot upgrade instead of a dead CTA', () => {
    render(<UpgradeGate feature="campaign templates" canManageBilling={false} />);

    expect(screen.queryByRole('button', { name: /upgrade to pro/i })).not.toBeInTheDocument();
    expect(
      screen.getByText(/Only a workspace owner or admin can upgrade the plan/i),
    ).toBeInTheDocument();
  });

  it('shows a toast and re-enables the CTA when checkout fails to start', async () => {
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    initiateCheckoutMock.mockRejectedValue(new ApiError('RAZORPAY_UNAVAILABLE', 'Checkout is temporarily unavailable', 502));
    const user = userEvent.setup({ delay: null });
    render(<UpgradeGate feature="report exports" canManageBilling />);

    await user.click(screen.getByRole('button', { name: /upgrade to pro/i }));

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Could not start checkout', variant: 'destructive' }),
      ),
    );
    expect(await screen.findByRole('button', { name: /upgrade to pro/i })).toBeEnabled();
  });
});
