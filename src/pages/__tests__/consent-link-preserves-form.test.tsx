/**
 * F-0293 (consent-link-destroys-form-state) — brand-register.tsx / creator-register.tsx.
 *
 * Both registration forms render "Terms of Service" / "Privacy Policy" as plain
 * <a href="/terms">/<a href="/privacy"> with no `target`. In this Vite SPA that is a
 * same-document navigation: following the link tears down the whole React tree and every live
 * useState with it (companyName/industry/teamSize/email/password/confirmPassword on the brand
 * form; name/email/password/confirmPassword on the creator form). A user who wants to read the
 * Terms before agreeing loses their entire in-progress registration.
 *
 * The existing F-0255 suite (brand-register.consent-links.test.tsx) already proves these are
 * real <a href> links, not dead buttons — it does not check what happens to the form if the
 * link is followed. This suite closes that gap: it types into live fields, then asserts the
 * links carry target="_blank" rel="noopener noreferrer" (so a real browser opens Terms/Privacy
 * in a new tab, leaving this document — and its live form state — untouched) rather than being
 * a same-document navigation that would discard everything just typed.
 *
 * Run: npx vitest run src/pages/__tests__/consent-link-preserves-form.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import BrandRegisterPage from '../brand-register';
import CreatorRegisterPage from '../creator-register';

const navigateMock = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const sendBrandEmailOtp = vi.fn();
const verifyBrandEmail = vi.fn();
const brandRegister = vi.fn();
const creatorRegister = vi.fn();
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
        creatorRegister: (...a: unknown[]) => creatorRegister(...a),
        setToken: vi.fn(),
      },
      config: { public: () => publicConfig() },
    },
  };
});

async function completeStep1(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText(/brand or company name/i), 'Audit Test Brand');
  await user.click(screen.getByRole('combobox', { name: /Industry/i }));
  await user.click(await screen.findByRole('option', { name: 'Technology' }));
  await user.click(screen.getByRole('combobox', { name: /Team Size/i }));
  await user.click(await screen.findByRole('option', { name: /6–20 people/ }));
  await user.click(screen.getByRole('button', { name: /^Next$/i }));
}

describe('brand-register.tsx — consent links do not destroy in-progress form state (F-0293)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    publicConfig.mockResolvedValue({ requireEmailOtp: false });
  });

  it('Terms/Privacy links open in a new tab instead of navigating this document away', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter initialEntries={['/brand/register']}>
        <BrandRegisterPage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(publicConfig).toHaveBeenCalled());
    await completeStep1(user);

    // Live, unsaved field state the user would lose if the link were a same-document nav.
    await user.type(screen.getByLabelText(/Email Address/i), 'founder@audittest.com');
    await user.type(screen.getByLabelText(/^Password/i), 'Sup3rSecret!');

    const termsLink = screen.getByRole('link', { name: 'Terms of Service' });
    const privacyLink = screen.getByRole('link', { name: 'Privacy Policy' });

    // The fix: a real browser must open these in a new tab/context, never unload this document.
    expect(termsLink).toHaveAttribute('target', '_blank');
    expect(termsLink).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(privacyLink).toHaveAttribute('target', '_blank');
    expect(privacyLink).toHaveAttribute('rel', expect.stringContaining('noopener'));

    // The in-progress fields are still here — proof the link's presence didn't itself disturb them.
    expect(screen.getByLabelText(/Email Address/i)).toHaveValue('founder@audittest.com');
    expect(screen.getByLabelText(/^Password/i)).toHaveValue('Sup3rSecret!');
  });
});

describe('creator-register.tsx — consent links do not destroy in-progress form state (F-0293)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('Terms/Privacy links open in a new tab instead of navigating this document away', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <MemoryRouter initialEntries={['/creator/register']}>
        <CreatorRegisterPage />
      </MemoryRouter>,
    );

    await user.type(screen.getByLabelText(/Your Name/i), 'Priya Sharma');
    await user.type(screen.getByLabelText(/Email Address/i), 'priya@creator.com');

    const termsLink = screen.getByRole('link', { name: 'Terms of Service' });
    const privacyLink = screen.getByRole('link', { name: 'Privacy Policy' });

    expect(termsLink).toHaveAttribute('target', '_blank');
    expect(termsLink).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(privacyLink).toHaveAttribute('target', '_blank');
    expect(privacyLink).toHaveAttribute('rel', expect.stringContaining('noopener'));

    expect(screen.getByLabelText(/Your Name/i)).toHaveValue('Priya Sharma');
    expect(screen.getByLabelText(/Email Address/i)).toHaveValue('priya@creator.com');
  });
});
