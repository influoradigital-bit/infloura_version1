import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { UseBillingResult } from '@/hooks/brand/useBilling';

// GET /billing/plan `priceInr` and GET /billing/invoices `amount` are PAISE on the wire
// (V55 seeds plans.price_inr = 499900 for Pro; InvoiceService stores Razorpay's amountInPaise).
// The page used to feed both straight into a rupee formatter, so Pro read "₹4,99,900 per month".
const billing: UseBillingResult = {
  plan: {
    plan: {
      code: 'PRO',
      name: 'Pro',
      priceInr: 499900,
      billingCycle: 'MONTHLY',
      feeBps: 700,
      aiMonthlyAllotment: 1500,
      seatLimit: 5,
      trackedCreatorLimit: null,
      creatorAnalyticsMonthlyLimit: null,
      exportEnabled: true,
      campaignTemplatesEnabled: true,
    },
    subscription: {
      status: 'ACTIVE',
      currentPeriodStart: '2026-09-01T00:00:00Z',
      currentPeriodEnd: '2026-10-01T00:00:00Z',
      cancelAtPeriodEnd: false,
    },
  },
  usage: {
    periodStart: '2026-09-01',
    trackedCreatorsUsed: 0,
    trackedCreatorLimit: null,
    analyticsViewsUsed: 0,
    analyticsViewsLimit: null,
    exportEnabled: true,
    aiCreditsRemaining: 1500,
    aiCreditsMonthlyAllotment: 1500,
    activeSeatsUsed: 1,
  },
  invoices: [
    {
      id: '01KXINVOICE0000000000000001',
      amount: 589882, // Rs 5,898.82 — a GST-inclusive amount that is not a whole rupee
      status: 'PAID',
      periodStart: '2026-09-01T00:00:00Z',
      periodEnd: '2026-10-01T00:00:00Z',
      issuedAt: '2026-09-01T00:00:00Z',
      paidAt: '2026-09-01T00:00:00Z',
      pdfDownloadUrl: '/billing/invoices/01KXINVOICE0000000000000001/pdf',
    },
  ],
  campaignInvoices: [],
  commissionInvoices: [],
  isLoading: false,
  error: null,
  refetch: () => {},
};

vi.mock('@/hooks/brand/useBilling', () => ({ useBilling: () => billing }));
vi.mock('@/hooks/brand/useBrandBillingAccess', () => ({
  useBrandBillingAccess: () => ({ role: 'OWNER', canManage: true, isLoading: false }),
}));

import BrandBillingSettingsPage, { formatPaise } from './brand-billing-settings';

describe('BrandBillingSettingsPage — paise on the wire, rupees on screen', () => {
  it('formatPaise divides by 100 and keeps paise only when there are any', () => {
    expect(formatPaise(499900)).toBe('₹4,999');
    expect(formatPaise(589882)).toBe('₹5,898.82');
    expect(formatPaise(0)).toBe('₹0');
  });

  it('renders the Pro plan price as ₹4,999, never the raw paise figure', () => {
    render(
      <MemoryRouter>
        <BrandBillingSettingsPage />
      </MemoryRouter>,
    );
    expect(screen.getByText('₹4,999')).toBeInTheDocument();
    expect(screen.queryByText(/4,99,900/)).not.toBeInTheDocument();
  });

  it('renders a subscription invoice amount in rupees, never the raw paise figure', () => {
    render(
      <MemoryRouter>
        <BrandBillingSettingsPage />
      </MemoryRouter>,
    );
    expect(screen.getByText('₹5,898.82')).toBeInTheDocument();
    expect(screen.queryByText(/5,89,882/)).not.toBeInTheDocument();
  });
});
