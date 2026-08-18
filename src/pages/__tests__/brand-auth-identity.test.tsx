/**
 * Brand login / register — F-0320 (persisted-value-no-consumer), closing the loop.
 *
 * F-0282 taught `persistBrandSession` to keep the backend's real `displayName` on every brand
 * `TokenPair` and exposed `getBrandDisplayName()` to read it back — but nothing in production
 * ever called `getBrandDisplayName()`, and nothing in the brand login/register flow ever called
 * `useAuthStore().login()`/`setUser()` at all (only the creator flow does, per CR-06). A live
 * brand session's `useAuthStore().user` therefore stayed `null` forever, and the dashboard
 * greeting rendered "Good morning, there" no matter what the backend actually knew about the
 * signed-in person — the exact symptom the F-0282 record originally described, reproduced
 * unchanged by the fix that claimed to close it.
 *
 * This suite drives the real login/register forms with `userEvent` (synthetic `.click()` gives
 * false results on some components in this repo) and asserts the SHARED STORE afterwards — not
 * that some function got referenced somewhere. See dashboard-page-greeting.test.tsx for the
 * matching consumer-side half (the store being correct is necessary but not sufficient; the
 * dashboard must actually read it).
 *
 * Run: npx vitest run src/pages/__tests__/brand-auth-identity.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '@/lib/store';
import BrandLoginPage from '../brand-login';
import BrandRegisterPage from '../brand-register';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const brandLogin = vi.fn();
const brandRegister = vi.fn();
const publicConfig = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      ...actual.api,
      auth: {
        ...actual.api.auth,
        brandLogin: (...a: unknown[]) => brandLogin(...a),
        brandRegister: (...a: unknown[]) => brandRegister(...a),
        setToken: vi.fn(),
      },
      config: { public: () => publicConfig() },
    },
  };
});

function resetStore() {
  useAuthStore.setState({ user: null, workspace: null, isAuthenticated: false, isLoading: false });
}

beforeEach(() => {
  vi.clearAllMocks();
  window.localStorage.clear();
  resetStore();
});

describe('BrandLoginPage — F-0320 populates the shared auth store', () => {
  it('a real backend displayName reaches useAuthStore().user after login, not a null/placeholder identity', async () => {
    // Stands in for what persistBrandSession (F-0282, already correct — see auth-session.ts)
    // would have just written to localStorage from a real TokenPair. brandLogin is mocked at
    // the api boundary here, so its own internal call to persistBrandSession never runs; this
    // is exactly what it would have left behind.
    window.localStorage.setItem('brand_display_name', 'Priya Sharma');
    brandLogin.mockResolvedValue({ token: 'tok_live', userId: 'u_42', onboardingComplete: true });

    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter initialEntries={['/brand/login']}>
        <BrandLoginPage />
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/Email address/i), 'ops@realbrand.com');
    await user.type(screen.getByLabelText(/^Password$/i), 'Sup3rSecret!');
    await user.click(screen.getByRole('button', { name: /Sign in/i }));

    await waitFor(() => expect(brandLogin).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(navigateMock).toHaveBeenCalled());

    const storeUser = useAuthStore.getState().user;
    expect(storeUser, 'useAuthStore().user is still null after a successful brand login').not.toBeNull();
    expect(storeUser?.displayName).toBe('Priya Sharma');
    expect(storeUser?.email).toBe('ops@realbrand.com');
    expect(storeUser?.id).toBe('u_42');
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
  });

  it('an absent backend displayName falls back to an honest empty string, never a fabricated placeholder', async () => {
    // No 'brand_display_name' written at all — the honest "backend genuinely sent nothing" case.
    brandLogin.mockResolvedValue({ token: 'tok_live', userId: 'u_7', onboardingComplete: true });

    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter initialEntries={['/brand/login']}>
        <BrandLoginPage />
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/Email address/i), 'noname@realbrand.com');
    await user.type(screen.getByLabelText(/^Password$/i), 'Sup3rSecret!');
    await user.click(screen.getByRole('button', { name: /Sign in/i }));

    await waitFor(() => expect(brandLogin).toHaveBeenCalledTimes(1));

    const storeUser = useAuthStore.getState().user;
    expect(storeUser?.displayName).toBe('');
    expect(storeUser?.displayName).not.toMatch(/Brand Account/i);
  });
});

describe('BrandRegisterPage — F-0320 populates the shared auth store', () => {
  beforeEach(() => {
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
  });

  it('a real backend displayName reaches useAuthStore().user after registration', async () => {
    window.localStorage.setItem('brand_display_name', 'Rahul Mehta');
    brandRegister.mockResolvedValue({ token: 'tok_new', userId: 'u_99', onboardingComplete: false });

    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter initialEntries={['/brand/register']}>
        <BrandRegisterPage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await user.type(screen.getByPlaceholderText(/brand or company name/i), 'Audit Test Brand');
    await user.click(screen.getByRole('combobox', { name: /Industry/i }));
    await user.click(await screen.findByRole('option', { name: 'Technology' }));
    await user.click(screen.getByRole('combobox', { name: /Team Size/i }));
    await user.click(await screen.findByRole('option', { name: /6–20 people/ }));
    await user.click(screen.getByRole('button', { name: /^Next$/i }));

    await user.type(screen.getByLabelText(/Email Address/i), 'rahul@auditbrand.com');
    await user.type(screen.getByLabelText(/^Password/i), 'Passw0rd!!');
    await user.type(screen.getByLabelText(/^Confirm Password/i), 'Passw0rd!!');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));

    const storeUser = useAuthStore.getState().user;
    expect(storeUser, 'useAuthStore().user is still null after a successful brand registration').not.toBeNull();
    expect(storeUser?.displayName).toBe('Rahul Mehta');
    expect(storeUser?.email).toBe('rahul@auditbrand.com');
    expect(storeUser?.id).toBe('u_99');
  });
});
