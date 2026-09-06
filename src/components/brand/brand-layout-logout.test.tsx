/**
 * BrandLayout — sidebar logout server invalidation (F-0460).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The brand sidebar/mobile-dropdown "Log out" path used to clear only client-side state
 * (`logout()` + `localStorage.removeItem('brand_token')`) and never called the server. The
 * HttpOnly refresh cookie / refresh token stayed live in the DB, so a brand user who "logged
 * out" this way could silently get a new access token via `/auth/refresh` — the session could
 * be resurrected. The fix calls `api.auth.logout('brand')` first (mirroring the
 * already-reviewed CreatorLayout pattern, CR-90/CR-91 — see
 * src/components/creator/creator-layout-logout.test.tsx), then always clears every local
 * identity/onboarding key even if the server call fails.
 *
 * HARNESS NOTES — mirrors src/components/brand/dashboard/__tests__/dashboard-page.test.tsx's
 * `vi.mock('@/lib/api', ...)` / `vi.mock('@/lib/store', ...)` shape for BrandLayout.
 *
 * Run: npx vitest run src/components/brand/brand-layout-logout.test.tsx
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrandLayout } from './brand-layout';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const authStoreLogoutMock = vi.fn();
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ user: null, logout: (...a: unknown[]) => authStoreLogoutMock(...a) }),
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

const apiLiveMock = vi.fn();
const authLogoutMock = vi.fn().mockResolvedValue({ message: 'ok' });
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
    isApiLive: () => apiLiveMock(),
    api: {
      ...actual.api,
      auth: { ...actual.api.auth, logout: (...a: unknown[]) => authLogoutMock(...a) },
      workspaces: { ...actual.api.workspaces, getMe: (...a: unknown[]) => getMeMock(...a) },
    },
  };
});

const BRAND_LOCAL_KEYS = [
  'brand_token',
  'brand_user_id',
  'brand_email',
  'brand_display_name',
  'brand_company',
  'brand_workspace_id',
  'brand_onboarding_complete',
  'onboarding_complete',
] as const;

function seedLocalStorage() {
  for (const key of BRAND_LOCAL_KEYS) {
    localStorage.setItem(key, `stub-${key}`);
  }
}

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

/** Opens the sidebar account menu and clicks "Log out", then confirms in the dialog. */
async function logOutViaSidebar(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getAllByText('Test Brand').length).toBeGreaterThan(0));
  const trigger = screen.getAllByRole('button', { name: /Test Brand/ })[0];
  await user.click(trigger);
  const menuLogout = await screen.findAllByText('Log out');
  // The dropdown item, not yet the confirm-dialog action — click the first hit.
  await user.click(menuLogout[0]);
  const dialogAction = await screen.findByRole('button', { name: 'Log out' });
  await user.click(dialogAction);
}

describe('BrandLayout — sidebar logout (F-0460)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiLiveMock.mockReturnValue(true);
    authLogoutMock.mockResolvedValue({ message: 'ok' });
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
    localStorage.clear();
    seedLocalStorage();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('calls the server logout endpoint and clears local state', async () => {
    const user = userEvent.setup({ delay: null });
    renderLayout();

    await logOutViaSidebar(user);

    await waitFor(() => expect(authLogoutMock).toHaveBeenCalledWith('brand'));
    await waitFor(() => expect(authStoreLogoutMock).toHaveBeenCalled());
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/brand/login'));

    for (const key of BRAND_LOCAL_KEYS) {
      expect(localStorage.getItem(key)).toBeNull();
    }

    // Regression guard: the server call must not be dropped even if a future refactor
    // reorders these — assert call ORDER, not just that both happened.
    const logoutCallOrder = authLogoutMock.mock.invocationCallOrder[0];
    const clearCallOrder = authStoreLogoutMock.mock.invocationCallOrder[0];
    expect(logoutCallOrder).toBeLessThan(clearCallOrder);
  });

  it('still clears local state and navigates away even when the server call fails', async () => {
    authLogoutMock.mockRejectedValueOnce(new Error('network down'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup({ delay: null });
    renderLayout();

    await logOutViaSidebar(user);

    await waitFor(() => expect(authLogoutMock).toHaveBeenCalledWith('brand'));
    await waitFor(() => expect(authStoreLogoutMock).toHaveBeenCalled());
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/brand/login'));

    for (const key of BRAND_LOCAL_KEYS) {
      expect(localStorage.getItem(key)).toBeNull();
    }
    expect(consoleError).toHaveBeenCalled();

    consoleError.mockRestore();
  });
});
