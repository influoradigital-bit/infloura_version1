/**
 * INFLUORA ADMIN PANEL — FestivalCouponsPanel tests
 * Owner: Ananya (Frontend)
 * Reference: T-FESTIVALBOX-0905 phase 12 (screen 1, live)
 *
 * This panel used to render every control genuinely `disabled` (no admin-authorized backend
 * route existed). `AdminCampaignCouponController` closed that gap, so this file is the inverse
 * of its predecessor: it asserts the controls are ENABLED, the real `festivalCouponApi.create`/
 * `.list` calls fire with the right shapes, `BRAND_CODE_EXISTS` renders its own specific message
 * (not a generic failure), and the confirm step's double-fire guard actually holds synchronously
 * — not just because React had already disabled the button by the second click.
 *
 * Run: npx vitest run src/admin/components/festival/FestivalCouponsPanel.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, render as rtlRender, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { FestivalCouponsPanel } from './FestivalCouponsPanel';

// FestivalCouponsPanel calls useQuery()/useMutation(), so the tree needs a QueryClientProvider
// — in the app that comes from App.tsx. Retries off: a failing query/mutation should surface
// immediately rather than stall the test. Same shape as FlagQueue.test.tsx/
// CreatorConnectionsPage.test.tsx's render helpers.
function render(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return rtlRender(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

const toastFn = vi.fn();
vi.mock('@/hooks/use-toast', () => ({ useToast: () => ({ toast: toastFn }) }));

const listMock = vi.fn();
const createMock = vi.fn();
vi.mock('../../services/api-contracts', async () => {
  const actual = await vi.importActual<typeof import('../../services/api-contracts')>(
    '../../services/api-contracts',
  );
  return {
    ...actual,
    festivalCouponApi: {
      ...actual.festivalCouponApi,
      list: (...args: unknown[]) => listMock(...args),
      create: (...args: unknown[]) => createMock(...args),
    },
  };
});

const EXISTING_COUPON = {
  id: 'cpn_1',
  campaignId: 'cmp_test123',
  creatorProfileId: null,
  code: 'ACME15',
  discountType: 'percentage',
  discountValue: 15,
  usageLimit: null,
  usageCount: 4,
  expiresAt: null,
  createdAt: '2026-09-01T00:00:00Z',
};

function renderPanel() {
  return render(<FestivalCouponsPanel campaignId="cmp_test123" sponsorName="Acme Foods" />);
}

async function fillValidBrandLevelForm(user: ReturnType<typeof userEvent.setup>) {
  const discountValueInput = screen.getByLabelText(/^Percentage$/i);
  await user.clear(discountValueInput);
  await user.type(discountValueInput, '15');
}

describe('FestivalCouponsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockResolvedValue({ success: true, data: { coupons: [EXISTING_COUPON] } });
    createMock.mockResolvedValue({
      success: true,
      data: {
        id: 'cpn_2',
        campaignId: 'cmp_test123',
        creatorProfileId: null,
        code: 'ACME_NEW',
        discountType: 'percentage',
        discountValue: 15,
        usageLimit: null,
        usageCount: 0,
        expiresAt: null,
        createdAt: '2026-09-06T00:00:00Z',
      },
    });
  });

  it('renders every coupon control as enabled, with the operational code-generation note (no "not available" copy)', async () => {
    renderPanel();

    expect(screen.getByText('Sponsor coupon codes')).toBeInTheDocument();
    expect(screen.getByText(/cmp_test123/)).toBeInTheDocument();
    expect(screen.getByText(/Acme Foods/)).toBeInTheDocument();

    // The stale "Not available yet" disabled-reason copy must be gone.
    expect(screen.queryByText(/not available yet/i)).not.toBeInTheDocument();
    // The corrected operational fact must be present instead.
    expect(screen.getByText(/Influora generates the code — you place it/i)).toBeInTheDocument();
    expect(screen.getByText(/dead at their checkout/i)).toBeInTheDocument();

    // No <fieldset disabled> wrapper anymore, and every interactive control is enabled.
    expect(document.querySelector('fieldset')).not.toBeInTheDocument();
    for (const el of screen.getAllByRole('radio')) {
      expect(el).toBeEnabled();
    }
    expect(screen.getByLabelText(/^Percentage$/i)).toBeEnabled();
    expect(screen.getByLabelText(/Usage limit/i)).toBeEnabled();
    expect(screen.getByLabelText(/Expires/i)).toBeEnabled();

    await waitFor(() => expect(listMock).toHaveBeenCalledWith('cmp_test123'));
  });

  it('shows a loading state, then the existing coupons for this campaign', async () => {
    let resolveList: (v: unknown) => void = () => {};
    listMock.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveList = resolve;
      }),
    );

    renderPanel();

    expect(screen.getByText(/Loading coupons/i)).toBeInTheDocument();

    act(() => {
      resolveList({ success: true, data: { coupons: [EXISTING_COUPON] } });
    });

    await waitFor(() => expect(screen.getByText('ACME15')).toBeInTheDocument());
    expect(screen.getByText('Brand-level')).toBeInTheDocument();
    expect(screen.getByText('15%')).toBeInTheDocument();
    // usageLimit null renders via orDash, never "0" or a blank cell.
    expect(screen.getByText('4 / —')).toBeInTheDocument();
  });

  it('shows an empty state when the campaign has no coupons yet', async () => {
    listMock.mockResolvedValueOnce({ success: true, data: { coupons: [] } });

    renderPanel();

    await waitFor(() =>
      expect(screen.getByText(/No coupons registered yet for this campaign/i)).toBeInTheDocument(),
    );
  });

  it('registers a brand-level coupon with the confirmed terms and shows the generated code', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    await waitFor(() => expect(listMock).toHaveBeenCalled());

    await fillValidBrandLevelForm(user);

    await user.click(screen.getByRole('button', { name: /register coupon/i }));
    await waitFor(() =>
      expect(screen.getByText(/Register this coupon\?/i)).toBeInTheDocument(),
    );

    await user.click(screen.getByRole('button', { name: /confirm & register/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith('cmp_test123', {
        creatorProfileId: undefined,
        discountType: 'percentage',
        discountValue: 15,
        usageLimit: undefined,
        expiresAt: undefined,
      }),
    );

    // "ACME_NEW" is its own <span>, so the sentence is split across nodes — find the span
    // itself, then assert the surrounding paragraph reads as the full success sentence.
    const codeSpan = await screen.findByText('ACME_NEW');
    expect(codeSpan.closest('p')).toHaveTextContent(
      /Code ACME_NEW registered — create this exact code in Acme Foods's store now\./,
    );
    expect(toastFn).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Coupon registered',
        description: expect.stringContaining('ACME_NEW'),
      }),
    );
  });

  it('registers a per-creator coupon when that kind is selected', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    await waitFor(() => expect(listMock).toHaveBeenCalled());

    await user.click(screen.getByRole('radio', { name: /Per-creator/i }));
    await user.type(screen.getByLabelText(/Creator profile ID/i), 'cr_abc123');
    await fillValidBrandLevelForm(user);

    await user.click(screen.getByRole('button', { name: /register coupon/i }));
    await waitFor(() =>
      expect(screen.getByText(/Register this coupon\?/i)).toBeInTheDocument(),
    );
    await user.click(screen.getByRole('button', { name: /confirm & register/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith('cmp_test123', {
        creatorProfileId: 'cr_abc123',
        discountType: 'percentage',
        discountValue: 15,
        usageLimit: undefined,
        expiresAt: undefined,
      }),
    );
  });

  it('renders BRAND_CODE_EXISTS as its own specific message, not a generic failure', async () => {
    createMock.mockResolvedValueOnce({
      success: false,
      error: 'This campaign already has a brand-level coupon — only one is allowed per campaign.',
      code: 'BRAND_CODE_EXISTS',
    });

    const user = userEvent.setup({ delay: null });
    renderPanel();
    await waitFor(() => expect(listMock).toHaveBeenCalled());

    await fillValidBrandLevelForm(user);
    await user.click(screen.getByRole('button', { name: /register coupon/i }));
    await waitFor(() =>
      expect(screen.getByText(/Register this coupon\?/i)).toBeInTheDocument(),
    );
    await user.click(screen.getByRole('button', { name: /confirm & register/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      'This campaign already has a brand-level coupon — only one is allowed per campaign.',
    );
    // Never collapsed to a generic fallback string.
    expect(alert).not.toHaveTextContent(/please try again/i);
  });

  it('double-fire guard: two clicks dispatched inside one act() call still only register once', async () => {
    // A deliberately never-resolving create() so the in-flight window stays open long enough
    // that a missing guard would provably double-fire — resolution timing can't save this test.
    createMock.mockReturnValueOnce(new Promise(() => {}));

    const user = userEvent.setup({ delay: null });
    renderPanel();
    await waitFor(() => expect(listMock).toHaveBeenCalled());

    await fillValidBrandLevelForm(user);
    await user.click(screen.getByRole('button', { name: /register coupon/i }));
    await waitFor(() =>
      expect(screen.getByText(/Register this coupon\?/i)).toBeInTheDocument(),
    );

    const confirmButton = screen.getByRole('button', { name: /confirm & register/i });

    // Both clicks dispatched inside ONE act() call: React does not flush/commit any state
    // update between them, so a plain `fireEvent.click` per call (which RTL auto-wraps and
    // flushes individually) would already show the button disabled by the second call and pass
    // even with no guard at all. This proves the synchronous ref check
    // (`createInFlightRef`), not React's re-render, is what stops the second submission. The
    // click handler itself (and the ref check/set inside it) runs synchronously on each
    // dispatch; only the mutation's own call to `festivalCouponApi.create` is deferred a tick
    // by react-query's internal scheduling, hence the `waitFor` below.
    act(() => {
      fireEvent.click(confirmButton);
      fireEvent.click(confirmButton);
    });

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(createMock).toHaveBeenCalledTimes(1);
  });
});
