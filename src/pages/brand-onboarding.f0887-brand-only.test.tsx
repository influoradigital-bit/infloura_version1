/**
 * F-0887 — onboarding promises an Agency product that does not exist.
 *
 * Swapnil's ruling (wiki/decisions/2026-09-18-agency-chooser-removed.md, 2026-09-18): the Agency
 * workspace type is removed from onboarding. An AGENCY workspace got no `subscriptions` row (the
 * backfill migration is scoped to `type = 'BRAND'`), was invisible in the admin billing console,
 * and never had its AI credits reset — so offering the choice was actively worse than not
 * offering it. `WorkspaceType.AGENCY` stays in the enum/DB (existing rows must still load); only
 * the chooser and the payload value are removed here (D2 in that doc, owned by Ananya).
 *
 * This pins two things:
 *   1. `CompanyDetailsStep` (onboarding-steps.tsx) never renders a Brand/Agency chooser at all —
 *      not "Brand is default", but the control itself is gone.
 *   2. The signup payload `saveBrandCompany` sends always carries `workspaceType: 'BRAND'`
 *      (brand-onboarding.tsx:102-108), regardless of any stale `OnboardingData.workspaceType`
 *      state.
 *
 * Falsification: assertion 1 was checked red by temporarily restoring the two-card `<button>`
 * grid in onboarding-steps.tsx (the chooser rendered, "Agency" was found); assertion 2 was
 * checked red by temporarily reverting the payload line to `workspaceType: data.workspaceType`
 * (still passed while `data.workspaceType` defaulted to 'BRAND' with no chooser to change it —
 * which is exactly why the payload is now a hardcoded literal, not a default that depends on
 * nothing overwriting it). Both reverted back to this file's current source afterward, never via
 * `git checkout`.
 *
 * Run: npx vitest run src/pages/brand-onboarding.f0887-brand-only.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BrandOnboardingPage from './brand-onboarding';
import { CompanyDetailsStep, initialData } from '@/components/brand/onboarding/onboarding-steps';

const sendBrandEmailOtp = vi.fn();
const verifyBrandEmail = vi.fn();
const brandRegister = vi.fn();
const saveBrandCompany = vi.fn();
const checkSlug = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false,
    api: {
      auth: {
        sendBrandEmailOtp: (...a: unknown[]) => sendBrandEmailOtp(...a),
        verifyBrandEmail: (...a: unknown[]) => verifyBrandEmail(...a),
        brandRegister: (...a: unknown[]) => brandRegister(...a),
      },
      onboarding: {
        saveBrandCompany: (...a: unknown[]) => saveBrandCompany(...a),
        completeBrand: vi.fn(),
      },
      workspaces: { checkSlug: (...a: unknown[]) => checkSlug(...a) },
    },
  };
});

vi.mock('@/hooks/use-toast', () => ({ toast: vi.fn() }));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/brand/onboarding']}>
        <BrandOnboardingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function completeStep1(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/First name/i), 'Rahul');
  await user.type(screen.getByLabelText(/Last name/i), 'Sharma');
  await user.type(screen.getByLabelText(/Work email/i), 'rahul@acme.com');
  await user.type(screen.getByLabelText(/Mobile number/i), '9876543210');
  await user.type(screen.getByLabelText('Password'), 'hunter2hunter2');
  await user.type(screen.getByLabelText(/Confirm password/i), 'hunter2hunter2');
  await user.click(screen.getByRole('checkbox'));
  await user.click(screen.getByRole('button', { name: /Send verification code/i }));

  await screen.findByText(/Verify your email/i);
  const otpBoxes = document.querySelectorAll('input[inputmode="numeric"]');
  await user.click(otpBoxes[0]);
  await user.paste('123456');
  await user.click(screen.getByRole('button', { name: /Verify email/i }));

  await screen.findByText(/Tell us about your brand/i, undefined, { timeout: 3000 });
}

describe('F-0887 — onboarding offers BRAND only', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sendBrandEmailOtp.mockResolvedValue({ sent: true });
    verifyBrandEmail.mockResolvedValue({ emailVerified: true });
    saveBrandCompany.mockResolvedValue({});
    checkSlug.mockResolvedValue({ slug: 'acme-co', available: true, suggestions: [] });
  });

  it('CompanyDetailsStep never renders a Brand/Agency chooser', () => {
    render(
      <CompanyDetailsStep
        data={initialData}
        onUpdate={vi.fn()}
        onNext={vi.fn()}
        onBack={vi.fn()}
      />,
    );

    expect(screen.queryByText('Workspace type')).not.toBeInTheDocument();
    expect(screen.queryByText('Agency')).not.toBeInTheDocument();
    expect(screen.queryByText('Multiple clients')).not.toBeInTheDocument();
    // "Brand" itself must not appear as a selectable card either — the whole control is gone,
    // not just narrowed to one option.
    expect(screen.queryByText('Single company')).not.toBeInTheDocument();
  });

  it('sends workspaceType: BRAND to saveBrandCompany on real signup, through the full step 1 -> step 2 flow', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await completeStep1(user);
    await user.type(screen.getByLabelText(/Company name/i), 'Acme Co');
    const comboboxes = screen.getAllByRole('combobox');
    await user.click(comboboxes[0]); // Industry
    await user.click(await screen.findByRole('option', { name: 'Technology' }));
    await user.click(comboboxes[1]); // Company size
    await user.click(await screen.findByRole('option', { name: /Startup/i }));

    await user.click(screen.getByRole('button', { name: /Continue/i }));

    await vi.waitFor(() => expect(saveBrandCompany).toHaveBeenCalledTimes(1));
    expect(saveBrandCompany).toHaveBeenCalledWith(
      expect.objectContaining({ workspaceType: 'BRAND' }),
    );
  });
});
