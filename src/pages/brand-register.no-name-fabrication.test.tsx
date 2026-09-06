/**
 * Brand registration — F-0463 (fabricated-data-in-ui).
 *
 * Before this fix, `submitRegistration` split the email's local part on
 * `[._-]` to invent `firstName`/`lastName` (e.g. `sales@acme.com` ->
 * firstName "Sales", lastName "User"), sent that fabricated name to
 * `POST /auth/brand/register`, and it flowed straight into the greeting via
 * `buildBrandUser({ displayName: getBrandDisplayName() })` — a human name
 * nobody typed, persisted and shown back to them.
 *
 * `BrandRegisterRequest` on the server is `@NotBlank` on both `firstName`
 * and `lastName` (influora-api/.../dto/auth/BrandRegisterRequest.java), so
 * the honest fix is real inputs on the form, not omitting the fields. This
 * suite proves the email is never used as a name source: registering with
 * an email whose local part looks like a name sends exactly what the user
 * typed into the First Name / Last Name fields — never a value derived from
 * the email — and that submission is blocked (no silent fabricated fallback)
 * when those fields are left blank.
 *
 * Run: npx vitest run src/pages/brand-register.no-name-fabrication.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandRegisterPage from './brand-register';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const sendBrandEmailOtp = vi.fn();
const brandRegister = vi.fn();
const publicConfig = vi.fn();
const loginMock = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        sendBrandEmailOtp: (...a: unknown[]) => sendBrandEmailOtp(...a),
        brandRegister: (...a: unknown[]) => brandRegister(...a),
        setToken: vi.fn(),
      },
      config: { public: () => publicConfig() },
    },
  };
});

// `login()` writes to the shared auth store; capture what it was actually called with so we can
// assert the greeted identity, without pulling in the real store's other side effects.
vi.mock('@/lib/store', () => ({
  useAuthStore: () => ({ login: loginMock }),
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/register']}>
      <BrandRegisterPage />
    </MemoryRouter>,
  );
}

async function completeStep1(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText(/brand or company name/i), 'Acme Corp');

  await user.click(screen.getByRole('combobox', { name: /Industry/i }));
  await user.click(await screen.findByRole('option', { name: 'Technology' }));

  await user.click(screen.getByRole('combobox', { name: /Team Size/i }));
  await user.click(await screen.findByRole('option', { name: /6–20 people/ }));

  await user.click(screen.getByRole('button', { name: /^Next$/i }));
}

describe('BrandRegisterPage — no fabricated name from email (F-0463)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
    brandRegister.mockResolvedValue({
      token: 't',
      userId: 'u_1',
      onboardingComplete: false,
    });
  });

  it('sends the name the user typed, not one derived from the email local part', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);

    // sales@acme.com — the exact shape F-0463 called out: naive splitting on `[._-]` would
    // fabricate firstName "Sales", lastName "User".
    await user.type(screen.getByPlaceholderText('Jane'), 'Priya');
    await user.type(screen.getByPlaceholderText('Doe'), 'Sharma');
    await user.type(screen.getByPlaceholderText('you@company.com'), 'sales@acme.com');
    await user.type(screen.getByPlaceholderText(/Create a strong password/i), 'Passw0rdy');
    await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));
    const payload = brandRegister.mock.calls[0][0];
    expect(payload.firstName).toBe('Priya');
    expect(payload.lastName).toBe('Sharma');
    // The old bug's exact fabricated values must never appear.
    expect(payload.firstName).not.toBe('Sales');
    expect(payload.lastName).not.toBe('User');
  });

  it('blocks submission and does not call brandRegister when First/Last Name are left blank', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);

    // Deliberately skip First Name / Last Name — only fill the rest of step 2.
    await user.type(screen.getByPlaceholderText('you@company.com'), 'sales@acme.com');
    await user.type(screen.getByPlaceholderText(/Create a strong password/i), 'Passw0rdy');
    await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: /Create Account/i }));

    expect(await screen.findByText(/First name is required/i)).toBeInTheDocument();
    expect(screen.getByText(/Last name is required/i)).toBeInTheDocument();
    expect(brandRegister).not.toHaveBeenCalled();
  });
});
