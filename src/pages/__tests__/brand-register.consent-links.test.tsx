/**
 * Brand registration — F-0255 (dead-legal-control).
 *
 * Step 2's mandatory consent checkbox blocks registration until it is checked, but the
 * "Terms of Service" / "Privacy Policy" copy next to it used to render as
 * `<button type="button">` with no onClick and no href — a dead control the user could not
 * open. Real content exists in this repo (src/content/legal/{terms-of-service,privacy-policy}.md,
 * routed at /terms and /privacy via LegalPage in App.tsx, and already linked correctly from
 * creator-register.tsx). The fix links brand-register.tsx to those same real routes.
 *
 * This suite asserts on rendered href/role rather than simulating a click — synthetic
 * `.click()` gives false "dead button" results on some components in this repo, and a real
 * <a href> doesn't need a click to prove it's reachable; the href and keyboard-focusability are
 * the proof.
 *
 * Run: npx vitest run src/pages/__tests__/brand-register.consent-links.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandRegisterPage from '../brand-register';

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

/** Step 1 is company details behind two Radix selects; step 2 is the credentials + consent form. */
async function completeStep1(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText(/brand or company name/i), 'Audit Test Brand');

  await user.click(screen.getByRole('combobox', { name: /Industry/i }));
  await user.click(await screen.findByRole('option', { name: 'Technology' }));

  await user.click(screen.getByRole('combobox', { name: /Team Size/i }));
  await user.click(await screen.findByRole('option', { name: /6–20 people/ }));

  await user.click(screen.getByRole('button', { name: /^Next$/i }));
}

describe('BrandRegisterPage — consent links reachable (F-0255)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
  });

  it('renders Terms of Service and Privacy Policy as real, focusable links to routes this app serves', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());
    await completeStep1(user);

    const termsLink = screen.getByRole('link', { name: 'Terms of Service' });
    const privacyLink = screen.getByRole('link', { name: 'Privacy Policy' });

    // Real hrefs to routes App.tsx actually serves (/terms, /privacy via LegalPage) — not a
    // dead <button type="button"> with no destination.
    expect(termsLink).toHaveAttribute('href', '/terms');
    expect(privacyLink).toHaveAttribute('href', '/privacy');

    // Keyboard-focusable: an <a href> is in the tab order by default (no tabIndex="-1"), unlike
    // a <button type="button"> with no handler that a screen reader announces as inert.
    termsLink.focus();
    expect(termsLink).toHaveFocus();
    privacyLink.focus();
    expect(privacyLink).toHaveFocus();
  });

  it('does not render Terms of Service / Privacy Policy as a dead <button type="button">', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());
    await completeStep1(user);

    expect(screen.queryByRole('button', { name: 'Terms of Service' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Privacy Policy' })).not.toBeInTheDocument();
  });
});
