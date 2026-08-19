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
