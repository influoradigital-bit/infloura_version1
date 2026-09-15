/**
 * BrandLayout — sidebar Billing nav entry (T-BILLNAV-0913).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * The only route to /brand/settings/billing was a navigate() button buried inside
 * the Settings page (src/pages/brand-settings.tsx:838). Zero of 31 production
 * workspaces have a subscriptions row — GET /billing/plan lazily creates a Free
 * row on first call, so zero rows proves no brand has ever completed that call.
 * This test locks in the fix: a Billing entry in the sidebar's Payments group
 * whose click target is /brand/settings/billing.
 *
 * HARNESS NOTES — mirrors src/components/brand/brand-layout-logout.test.tsx's
 * mocking shape for BrandLayout (same component, same dependencies to stub).
 *
 * Run: npx vitest run src/components/brand/brand-layout-billing-nav.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandLayout } from './brand-layout';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ user: null, logout: vi.fn() }),
  useUIStore: () => ({
    mobileMenuOpen: false,
    toggleMobileMenu: vi.fn(),
    setMobileMenuOpen: vi.fn(),
    closeMobileMenu: vi.fn(),
  }),
}));

vi.mock('@/hooks/useNotifications', () => ({
  useNotifications: () => ({
    notifications: [],
    unreadCount: 0,
    loading: false,
    error: null,
    refresh: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  }),
}));

vi.mock('@/components/brand/WorkspaceVerificationBanner', () => ({
  WorkspaceVerificationBanner: () => null,
}));
vi.mock('@/components/brand/command-bar', () => ({ CommandBar: () => null }));

const getMeMock = vi.fn().mockResolvedValue({
  id: 'ws_test',
  name: 'Test Brand',
  slug: 'test-brand',
  email: 'ops@testbrand.com',
  phone: null,
  industry: null,
  companySize: null,
  websiteUrl: null,
  description: null,
  logoUrl: null,
  verificationStatus: 'VERIFIED',
});

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      workspaces: { ...actual.api.workspaces, getMe: (...a: unknown[]) => getMeMock(...a) },
    },
  };
});

function renderLayout() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/dashboard']}>
        <BrandLayout>
          <div>page content</div>
        </BrandLayout>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BrandLayout — sidebar Billing nav entry (T-BILLNAV-0913)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getMeMock.mockResolvedValue({
      id: 'ws_test',
      name: 'Test Brand',
      slug: 'test-brand',
      email: 'ops@testbrand.com',
      phone: null,
      industry: null,
      companySize: null,
      websiteUrl: null,
      description: null,
      logoUrl: null,
      verificationStatus: 'VERIFIED',
    });
  });

  it('renders a Billing entry inside the sidebar nav landmark and navigates to /brand/settings/billing on click', async () => {
    const user = userEvent.setup({ delay: null });
    renderLayout();

    // Scope to the sidebar's <nav role="navigation"> landmark (brand-layout.tsx ~L329) so this
    // can only pass for a Billing control that actually lives in the sidebar nav — not one
    // planted anywhere else in the component (e.g. the header, or an avatar dropdown item).
    const sidebarNav = await screen.findByRole('navigation');
    const billingButton = await within(sidebarNav).findByRole('button', { name: 'Billing' });

    await user.click(billingButton);

    expect(navigateMock).toHaveBeenCalledWith('/brand/settings/billing');
  });
});
