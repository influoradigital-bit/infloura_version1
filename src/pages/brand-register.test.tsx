/**
 * Brand registration — email-OTP gate.
 *
 * This page matters more than the onboarding wizard for verification: it is the signup CTA
 * linked from the landing page, every /features page, both how-it-works pages, /about and the
 * blog. Before 2026-07-26 it called `brandRegister` directly with no OTP step, so the wizard's
 * OTP stage was only reachable by landing on /brand/onboarding with no token — which the CTA
 * path never does. Turning `influora.auth.require-email-otp-before-register` on would therefore
 * have 400'd the main funnel.
 *
 * Run: npx vitest run src/pages/brand-register.test.tsx
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
const verifyBrandEmail = vi.fn();
const brandRegister = vi.fn();
const publicConfig = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => true,
    api: {
      auth: {
        sendBrandEmailOtp: (...a: unknown[]) => sendBrandEmailOtp(...a),
        verifyBrandEmail: (...a: unknown[]) => verifyBrandEmail(...a),
        brandRegister: (...a: unknown[]) => brandRegister(...a),
        setToken: vi.fn(),
      },
      config: { public: () => publicConfig() },
    },
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/brand/register']}>
      <BrandRegisterPage />
    </MemoryRouter>,
  );
}

/** Step 1 is company details behind two Radix selects; step 2 is the credentials form. */
async function completeStep1(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText(/brand or company name/i), 'Audit Test Brand');

  await user.click(screen.getByRole('combobox', { name: /Industry/i }));
  await user.click(await screen.findByRole('option', { name: 'Technology' }));

  await user.click(screen.getByRole('combobox', { name: /Team Size/i }));
  await user.click(await screen.findByRole('option', { name: /6–20 people/ }));

  await user.click(screen.getByRole('button', { name: /^Next$/i }));
}

async function completeStep2(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('you@company.com'), 'brand@example.com');
  await user.type(screen.getByPlaceholderText(/Create a strong password/i), 'Passw0rdy');
  await user.type(screen.getByPlaceholderText('Confirm your password'), 'Passw0rdy');
  await user.click(screen.getByRole('checkbox'));
  await user.click(screen.getByRole('button', { name: /Create Account/i }));
}

describe('BrandRegisterPage — email OTP gate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sendBrandEmailOtp.mockResolvedValue({ message: 'sent', expiresIn: 300, maskedEmail: 'b***@example.com' });
    verifyBrandEmail.mockResolvedValue({ emailVerified: true, message: 'ok' });
    brandRegister.mockResolvedValue({ token: 't', userId: 'u_1', onboardingComplete: false });
  });

  it('registers directly when the server does not require OTP', async () => {
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);
    await completeStep2(user);

    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));
    expect(sendBrandEmailOtp).not.toHaveBeenCalled();
    expect(navigateMock).toHaveBeenCalledWith('/brand/onboarding');
  });

  it('sends and verifies an OTP before registering when the server requires it', async () => {
    publicConfig.mockResolvedValue({ requireEmailOtp: true });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);
    await completeStep2(user);

    await waitFor(() => expect(sendBrandEmailOtp).toHaveBeenCalledWith('brand@example.com'));
    expect(await screen.findByText(/Verify your email/i)).toBeInTheDocument();
    // The account must not exist yet — that was the whole point of the gate.
    expect(brandRegister).not.toHaveBeenCalled();

    const boxes = screen.getAllByRole('textbox').filter((el) => el.getAttribute('maxlength') === '1');
    expect(boxes).toHaveLength(6);
    await user.type(boxes[0], '654321');
    await user.click(screen.getByRole('button', { name: /Verify email/i }));

    await waitFor(() => expect(verifyBrandEmail).toHaveBeenCalledWith('brand@example.com', '654321'));
    await waitFor(() => expect(brandRegister).toHaveBeenCalledTimes(1));
  });

  it('returns to the form when registration fails after verification', async () => {
    publicConfig.mockResolvedValue({ requireEmailOtp: true });
    const { ApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    brandRegister.mockRejectedValue(new ApiError('EMAIL_TAKEN', 'That email is already registered', 409));
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());

    await completeStep1(user);
    await completeStep2(user);
    await screen.findByText(/Verify your email/i);

    const boxes = screen.getAllByRole('textbox').filter((el) => el.getAttribute('maxlength') === '1');
    await user.type(boxes[0], '654321');
    await user.click(screen.getByRole('button', { name: /Verify email/i }));

    // Dropping back to the form is what puts the error next to the email field it refers to,
    // rather than stranding the user on a verified-but-dead OTP panel.
    expect(await screen.findByText(/already registered/i)).toBeInTheDocument();
    expect(screen.queryByText(/Verify your email/i)).not.toBeInTheDocument();
    expect(navigateMock).not.toHaveBeenCalledWith('/brand/onboarding');
  });
});
