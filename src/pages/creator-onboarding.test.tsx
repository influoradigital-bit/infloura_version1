/**
 * Creator Onboarding — completion destination (F-0275).
 *
 * WHY THIS FILE EXISTS
 * ---------------------
 * Finishing onboarding used to navigate straight to '/creator/deals', skipping past
 * creator-dashboard.tsx's zero-state entirely even though a freshly-onboarded creator is
 * exactly the `isEmptyCreator` case that screen's CTAs (Explore campaigns, Complete
 * profile) exist for. This test drives the real 3-step flow (skip socials → fill the
 * minimum required profile fields → complete) and pins the final redirect to
 * '/creator/dashboard', matching creator-register.tsx's existing destination.
 *
 * Run: npx vitest run src/pages/creator-onboarding.test.tsx
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CreatorOnboardingPage from './creator-onboarding';
import { ApiError } from '@/lib/api';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const saveCreatorProfile = vi.fn();
const completeCreator = vi.fn();

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      onboarding: {
        connectCreatorSocial: vi.fn(),
        saveCreatorProfile: (...a: unknown[]) => saveCreatorProfile(...a),
        completeCreator: (...a: unknown[]) => completeCreator(...a),
      },
      // Step 1's mount effect reads this to reflect a real Meta OAuth redirect (CR-120);
      // this test never exercises that flow, so "never connected" is correct here.
      metaOAuth: {
        getLocalConnectionState: vi.fn().mockReturnValue({
          connected: false,
          scopes: [],
          accountType: null,
        }),
      },
    },
  };
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/creator/onboarding']}>
      <CreatorOnboardingPage />
    </MemoryRouter>,
  );
}

/** Drives step 1 (skip) and step 2 (minimum fields `canProceed()` requires) to reach step 3. */
async function reachYoureInStep(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /Skip for now/i }));

  await user.type(screen.getByLabelText(/Display name/i), 'Test Creator');
  await user.click(screen.getByRole('button', { name: 'Fashion & Lifestyle' }));
  await user.type(screen.getByPlaceholderText('Min'), '5000');

  await user.click(screen.getByRole('button', { name: /Continue/i }));
  await screen.findByText(/You're in!/i);
}

describe('CreatorOnboardingPage — completion destination (F-0275)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    saveCreatorProfile.mockResolvedValue({});
    completeCreator.mockResolvedValue({});
  });

  it('routes to /creator/dashboard on completion, not /creator/deals', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();

    await reachYoureInStep(user);
    await user.click(screen.getByRole('button', { name: /Go to Dashboard/i }));

    await waitFor(() => expect(completeCreator).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/creator/dashboard'));
    expect(navigateMock).not.toHaveBeenCalledWith('/creator/deals');
    expect(localStorage.getItem('creator_onboarding_completed')).toBe('true');
  });
});

/**
 * Creator Onboarding — Mobile number field (PHONE-0904).
 *
 * Mirrors the brand onboarding mobile-number field and creator Settings' Mobile Number
 * dialog (creator-settings.tsx), but Optional here (creator Settings already treats the
 * same underlying field as optional/clearable — see that file's PHONE-0829 P1 notes).
 * `canProceed()` still blocks Continue on a malformed number, and a 409 (duplicate phone)
 * must surface inline, next to the field, without advancing past step 2.
 *
 * Run: npx vitest run src/pages/creator-onboarding.test.tsx
 */
describe('CreatorOnboardingPage — Mobile number field (PHONE-0904)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    saveCreatorProfile.mockResolvedValue({});
    completeCreator.mockResolvedValue({});
  });

  /** Skips step 1 and fills only the fields `canProceed()` requires besides phone. */
  async function fillRequiredFieldsExceptPhone(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: /Skip for now/i }));
    await user.type(screen.getByLabelText(/Display name/i), 'Test Creator');
    await user.click(screen.getByRole('button', { name: 'Fashion & Lifestyle' }));
    await user.type(screen.getByPlaceholderText('Min'), '5000');
  }

  it('renders the Mobile number field with a +91 prefix', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await user.click(screen.getByRole('button', { name: /Skip for now/i }));

    expect(screen.getByLabelText(/Mobile number/i)).toBeInTheDocument();
    expect(screen.getByText('+91')).toBeInTheDocument();
  });

  it('blocks Continue on an invalid mobile number and does not call saveCreatorProfile', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await fillRequiredFieldsExceptPhone(user);

    // '1234567890' fails the ^[6-9]\d{9}$ rule (leading digit not 6-9).
    await user.type(screen.getByLabelText(/Mobile number/i), '1234567890');

    expect(screen.getByRole('button', { name: /Continue/i })).toBeDisabled();
    expect(await screen.findByText(/Enter a valid 10-digit mobile number/i)).toBeInTheDocument();
    expect(saveCreatorProfile).not.toHaveBeenCalled();
  });

  it('includes a valid mobile number in the saveCreatorProfile payload', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await fillRequiredFieldsExceptPhone(user);

    await user.type(screen.getByLabelText(/Mobile number/i), '98765 43210');
    await user.click(screen.getByRole('button', { name: /Continue/i }));

    await waitFor(() => expect(saveCreatorProfile).toHaveBeenCalledTimes(1));
    expect(saveCreatorProfile).toHaveBeenCalledWith(
      expect.objectContaining({ phone: '9876543210' }),
    );
    await screen.findByText(/You're in!/i);
  });

  it('accepts a pasted "+91 9876543210" number and normalizes it before sending (PHONE-0904 parity fix)', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await fillRequiredFieldsExceptPhone(user);

    // Simulates a paste from contacts: userEvent.paste writes the whole string in one go,
    // the way a real clipboard paste does, so this exercises the onChange filter's
    // paste path directly rather than typing '+' key-by-key.
    const phoneInput = screen.getByLabelText(/Mobile number/i);
    await user.click(phoneInput);
    await user.paste('+91 9876543210');

    // Must NOT be blocked — this is exactly the false-negative Kavya's review flagged:
    // the old onChange filter stripped '+' but kept '91', producing 12 digits that failed
    // the raw /^[6-9]\d{9}$/ check even though the server would normalize and accept it.
    expect(screen.queryByText(/Enter a valid 10-digit mobile number/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Continue/i })).not.toBeDisabled();

    await user.click(screen.getByRole('button', { name: /Continue/i }));

    await waitFor(() => expect(saveCreatorProfile).toHaveBeenCalledTimes(1));
    // The NORMALIZED 10-digit value must reach the API, not the raw '+91 9876543210'.
    expect(saveCreatorProfile).toHaveBeenCalledWith(
      expect.objectContaining({ phone: '9876543210' }),
    );
    await screen.findByText(/You're in!/i);
  });

  it('accepts a pasted "09876543210" (leading trunk 0) number and normalizes it before sending', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await fillRequiredFieldsExceptPhone(user);

    const phoneInput = screen.getByLabelText(/Mobile number/i);
    await user.click(phoneInput);
    await user.paste('09876543210');

    expect(screen.queryByText(/Enter a valid 10-digit mobile number/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Continue/i })).not.toBeDisabled();

    await user.click(screen.getByRole('button', { name: /Continue/i }));

    await waitFor(() => expect(saveCreatorProfile).toHaveBeenCalledTimes(1));
    expect(saveCreatorProfile).toHaveBeenCalledWith(
      expect.objectContaining({ phone: '9876543210' }),
    );
    await screen.findByText(/You're in!/i);
  });

  it('shows the duplicate-phone message on a PHONE_ALREADY_EXISTS 409 and does not advance to step 3', async () => {
    // PHONE-0904 Q6 fix — the real backend code is `PHONE_ALREADY_EXISTS`
    // (UserPhoneService.java:113, :123), not an arbitrary 'DUPLICATE_PHONE' string. The page
    // now branches on `err.code`, not the bare `err.status`, so the mock must carry the real
    // code for this to be a meaningful regression test.
    saveCreatorProfile.mockRejectedValueOnce(
      new ApiError('PHONE_ALREADY_EXISTS', 'An account with this phone number already exists', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await fillRequiredFieldsExceptPhone(user);

    await user.type(screen.getByLabelText(/Mobile number/i), '9876543210');
    await user.click(screen.getByRole('button', { name: /Continue/i }));

    expect(
      await screen.findByText(/This mobile number is already registered/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/You're in!/i)).not.toBeInTheDocument();
    // Still on step 2 — the Display name field (step 2 only) is still present.
    expect(screen.getByLabelText(/Display name/i)).toBeInTheDocument();
  });

  it('does NOT show the phone message on a differently-coded 409 (code-based branch, not status)', async () => {
    // Defends the PHONE-0904 Q6 fix itself: a bare `err.status === 409` check would show the
    // phone message for ANY 409 from this endpoint. Any other code must fall through to the
    // generic toast instead.
    saveCreatorProfile.mockRejectedValueOnce(
      new ApiError('SOME_OTHER_CONFLICT', 'Something else conflicted', 409),
    );
    const user = userEvent.setup({ delay: null });
    renderPage();
    await fillRequiredFieldsExceptPhone(user);

    await user.type(screen.getByLabelText(/Mobile number/i), '9876543210');
    await user.click(screen.getByRole('button', { name: /Continue/i }));

    await waitFor(() => expect(saveCreatorProfile).toHaveBeenCalledTimes(1));
    expect(
      screen.queryByText(/This mobile number is already registered/i),
    ).not.toBeInTheDocument();
  });
});
