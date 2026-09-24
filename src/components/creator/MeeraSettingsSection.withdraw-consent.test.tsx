/**
 * DPDP A6: withdrawing consent must be as easy as giving it. The backend route
 * (`DELETE /creator/agent-preferences/consent`, CreatorAgentController:90) shipped in Phase A and
 * its own javadoc says exactly that — but no screen ever called it, so a creator could switch
 * Meera on and had no way to switch her off again. Found by the 2026-09-24 contract audit.
 *
 * Run: npx vitest run src/components/creator/MeeraSettingsSection.withdraw-consent.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CreatorAgentPreferences } from '@/lib/api';

const { getPreferences, listConversations, withdrawConsent } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  listConversations: vi.fn(),
  withdrawConsent: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorAgentPrefs: {
        ...actual.api.creatorAgentPrefs,
        getPreferences: (...args: unknown[]) => getPreferences(...args),
        listConversations: (...args: unknown[]) => listConversations(...args),
        withdrawConsent: (...args: unknown[]) => withdrawConsent(...args),
      },
    },
  };
});

import { MeeraSettingsSection } from './MeeraSettingsSection';

const basePrefs: CreatorAgentPreferences = {
  reel_floor: 1200,
  story_set_floor: 800,
  post_floor: 1500,
  floor_currency: 'INR',
  excluded_categories: [],
  blocked_brands: [],
  approval_level: 0,
  creator_language: 'en-IN',
  brand_tone: 'FRIENDLY',
  working_hours_start: 9,
  working_hours_end: 18,
  working_hours_timezone: 'Asia/Kolkata',
  working_days: [1, 2, 3, 4, 5],
  weekly_sponsored_limit: 3,
  represented: false,
  agency_name: null,
  consent_accepted: true,
  consent_version: 'v1',
  rate_card_shareable: false,
  rate_card: null,
  negotiation_holdout: false,
  approved_draft_count: 0,
  level_up_eligible: false,
};

describe('MeeraSettingsSection — withdrawing consent', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPreferences.mockResolvedValue(basePrefs);
    listConversations.mockResolvedValue({ conversations: [] });
    withdrawConsent.mockResolvedValue(undefined);
  });

  it('offers a way to turn Meera off', async () => {
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(screen.getByTestId('withdraw-consent')).toBeInTheDocument());
  });

  it('asks first, and only calls the withdraw route once the creator confirms', async () => {
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(screen.getByTestId('withdraw-consent')).toBeInTheDocument());
    await user.click(screen.getByTestId('withdraw-consent'));

    // The dialog is up and nothing has been sent yet — a mis-tap must not withdraw consent.
    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeInTheDocument());
    expect(withdrawConsent).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Turn Meera off' }));

    await waitFor(() => expect(withdrawConsent).toHaveBeenCalledTimes(1));
  });

  it('cancelling leaves consent alone', async () => {
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(screen.getByTestId('withdraw-consent')).toBeInTheDocument());
    await user.click(screen.getByTestId('withdraw-consent'));
    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(withdrawConsent).not.toHaveBeenCalled();
  });
});
