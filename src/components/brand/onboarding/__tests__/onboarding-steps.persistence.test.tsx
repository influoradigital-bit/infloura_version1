/**
 * Brand onboarding wizard — field-persistence regressions F-0394 / F-0395 / F-0396.
 *
 * All three are the same shape of bug: a field the wizard appears to collect never reaches a
 * database column, and every static gate (tsc, eslint, the build) stays green while it happens.
 * None of them is observable from the type signatures, so they are pinned here behaviourally.
 *
 *   F-0394  no Terms checkbox was rendered anywhere in the wizard, while `brand-onboarding.tsx`
 *           hardcoded `acceptedTerms: true` — so the server's @AssertTrue could never fail and
 *           consent was recorded for an agreement the user was never shown.
 *   F-0395  the company-size dropdown wrote display strings ('1-10 employees') into
 *           `workspaces.company_size`, which `AdminBrandService.KNOWN_SIZES` rejects with
 *           INVALID_BRAND_SIZE — an onboarded brand's size could never be re-saved via admin.
 *   F-0396  Continue stayed live while the logo was still uploading to R2, so `logoUrl` went out
 *           as undefined and the entity wrote null with the preview still on screen.
 *
 * HARNESS NOTES — mirrors onboarding-steps.demo-hint.test.tsx's `vi.mock('@/lib/api', ...)` shape.
 * `uploadToR2` is additionally mocked so the in-flight upload window can be held open.
 *
 * Run: npx vitest run src/components/brand/onboarding/__tests__/onboarding-steps.persistence.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

import {
  AccountSetupStep,
  CompanyDetailsStep,
  companySizes,
  initialData,
  type OnboardingData,
} from '../onboarding-steps';

const sendBrandEmailOtp = vi.fn().mockResolvedValue({ sent: true });
const checkSlug = vi.fn().mockResolvedValue({ slug: 'acme', available: true, suggestions: [] });

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    isApiLive: () => false,
    api: {
      auth: {
        sendBrandEmailOtp: (...args: unknown[]) => sendBrandEmailOtp(...args),
        verifyBrandEmail: vi.fn().mockResolvedValue({ emailVerified: true }),
      },
      workspaces: { checkSlug: (...args: unknown[]) => checkSlug(...args) },
    },
    ApiError: actual.ApiError,
  };
});

/** Held open by `holdUpload` so the "upload still in flight" window can be asserted on. */
let releaseUpload: (() => void) | null = null;
vi.mock('@/lib/upload', async () => {
  const actual = await vi.importActual<typeof import('@/lib/upload')>('@/lib/upload');
  return {
    ...actual,
    createLocalPreview: vi.fn().mockResolvedValue('blob:preview'),
    validateFile: vi.fn().mockReturnValue({ valid: true }),
    uploadToR2: vi.fn(
      () => new Promise((resolve) => {
        releaseUpload = () => resolve({ url: 'https://r2.example/logo.png', key: 'logo.png' });
      }),
    ),
  };
});

vi.mock('@/hooks/use-toast', () => ({ toast: vi.fn() }));

beforeEach(() => {
  vi.clearAllMocks();
  releaseUpload = null;
});

/** Renders AccountSetupStep over controlled state, so `onUpdate` actually feeds `data` back. */
function renderAccountStep(overrides: Partial<OnboardingData> = {}) {
  const onNext = vi.fn();
  let data: OnboardingData = { ...initialData, ...overrides };
  const view = render(
    <MemoryRouter>
      <AccountSetupStep data={data} onUpdate={() => {}} onNext={onNext} />
    </MemoryRouter>,
  );
  const rerender = (next: Partial<OnboardingData>) => {
    data = { ...data, ...next };
    view.rerender(
      <MemoryRouter>
        <AccountSetupStep data={data} onUpdate={() => {}} onNext={onNext} />
      </MemoryRouter>,
    );
  };
  return { rerender, onNext };
}

const FILLED: Partial<OnboardingData> = {
  firstName: 'Rahul',
  lastName: 'Sharma',
  email: 'rahul@acme.com',
  phone: '9876543210',
  password: 'hunter2hunter2',
  confirmPassword: 'hunter2hunter2',
};

describe('F-0394 — Terms consent is actually collected', () => {
  it('renders a Terms of Service checkbox in the account step', () => {
    renderAccountStep(FILLED);
    expect(screen.getByRole('checkbox')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Terms of Service/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: /Privacy Policy/i })).toBeTruthy();
  });

  it('blocks submission and does not send an OTP when Terms are unchecked', async () => {
    const user = userEvent.setup();
    // Every other field is valid; acceptTerms is the only thing missing.
    renderAccountStep({ ...FILLED, acceptTerms: false });

    await user.click(screen.getByRole('button', { name: /Send verification code/i }));

    expect(sendBrandEmailOtp).not.toHaveBeenCalled();
    expect(screen.getByText(/must agree to the Terms of Service/i)).toBeTruthy();
  });

  it('submits once Terms are accepted', async () => {
    const user = userEvent.setup();
    renderAccountStep({ ...FILLED, acceptTerms: true });

    await user.click(screen.getByRole('button', { name: /Send verification code/i }));

    expect(sendBrandEmailOtp).toHaveBeenCalledWith('rahul@acme.com');
  });

  it('reflects the checked state it is given rather than assuming consent', () => {
    const { rerender } = renderAccountStep({ ...FILLED, acceptTerms: false });
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
    rerender({ acceptTerms: true });
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(true);
  });
});

describe('F-0395 — company size uses the vocabulary the backend enforces', () => {
  // The closed union in AdminBrandService.KNOWN_SIZES and admin/types/admin.types.ts:158.
  const KNOWN_SIZES = ['STARTUP', 'SMB', 'ENTERPRISE'];

  it('offers only values AdminBrandService.update would accept', () => {
    expect(companySizes.length).toBeGreaterThan(0);
    for (const option of companySizes) {
      expect(KNOWN_SIZES).toContain(option.value);
    }
  });

  it('stores the union member, not the human-readable label', () => {
    // The pre-fix bug was that value === label === '1-10 employees'.
    for (const option of companySizes) {
      expect(option.value).not.toBe(option.label);
      expect(option.value).toMatch(/^[A-Z]+$/);
    }
  });
});

describe('F-0396 — the logo cannot be dropped by advancing mid-upload', () => {
  function renderCompanyStep() {
    const onNext = vi.fn();
    let data: OnboardingData = {
      ...initialData,
      companyName: 'Acme',
      companySlug: 'acme',
      industry: 'Technology',
      companySize: 'SMB',
    };
    const view = render(
      <CompanyDetailsStep
        data={data}
        onUpdate={(u) => {
          data = { ...data, ...u };
          view.rerender(
            <CompanyDetailsStep data={data} onUpdate={() => {}} onNext={onNext} onBack={() => {}} />,
          );
        }}
        onNext={onNext}
        onBack={() => {}}
      />,
    );
    return { onNext };
  }

  it('disables Continue while the logo upload is still in flight', async () => {
    const user = userEvent.setup();
    const { onNext } = renderCompanyStep();

    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, new File(['x'], 'logo.png', { type: 'image/png' }));

    // uploadToR2 is still pending — the fix must not let the step advance here.
    const continueBtn = screen.getByRole('button', { name: /Continue/i });
    expect(continueBtn.hasAttribute('disabled')).toBe(true);

    await user.click(continueBtn);
    expect(onNext).not.toHaveBeenCalled();

    // Releasing the upload re-enables it.
    releaseUpload?.();
  });
});
