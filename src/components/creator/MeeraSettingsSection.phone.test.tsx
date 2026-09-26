/**
 * Camera knowledge v5 (2026-09-24) — "My phone" on the Meera settings page.
 *
 * The phone is saved through its own route (PUT /creator/agent-preferences/phone), on the same
 * Save button, and only when it changed. The full-replace PUT must never carry it: Java's
 * UpdatePreferencesRequest has no such field, and the full PUT must not be able to wipe it.
 *
 * Run: npx vitest run src/components/creator/MeeraSettingsSection.phone.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CreatorAgentPreferences } from '@/lib/api';

const { getPreferences, updatePreferences, updatePhoneModel, listConversations } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  updatePreferences: vi.fn(),
  updatePhoneModel: vi.fn(),
  listConversations: vi.fn(),
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
        updatePreferences: (...args: unknown[]) => updatePreferences(...args),
        updatePhoneModel: (...args: unknown[]) => updatePhoneModel(...args),
        listConversations: (...args: unknown[]) => listConversations(...args),
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
  consent_version: 'v2',
  rate_card_shareable: false,
  rate_card: null,
  negotiation_holdout: false,
  approved_draft_count: 0,
  level_up_eligible: false,
  phone_model: 'OPPO A78 5G',
  equipment: [],
  content_dislikes: [],
};

describe('MeeraSettingsSection — My phone', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPreferences.mockResolvedValue(basePrefs);
    updatePreferences.mockImplementation(async () => basePrefs);
    listConversations.mockResolvedValue({ conversations: [] });
  });

  it('shows the saved phone', async () => {
    render(<MeeraSettingsSection />);
    const field = await screen.findByLabelText('My phone');
    expect(field).toHaveValue('OPPO A78 5G');
  });

  it('saves a changed phone through its own route, and the full PUT never carries it', async () => {
    updatePhoneModel.mockResolvedValue({ ...basePrefs, phone_model: 'OPPO Reno 14 Pro' });
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    const field = await screen.findByLabelText('My phone');
    await user.clear(field);
    await user.type(field, '  OPPO Reno 14 Pro ');
    await user.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updatePhoneModel).toHaveBeenCalledWith('OPPO Reno 14 Pro'));
    expect(updatePreferences).toHaveBeenCalledTimes(1);
    expect(updatePreferences.mock.calls[0][0]).not.toHaveProperty('phone_model');
    await waitFor(() => expect(screen.getByLabelText('My phone')).toHaveValue('OPPO Reno 14 Pro'));
  });

  it('does not call the phone route when the phone did not change', async () => {
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);
    await screen.findByLabelText('My phone');
    await user.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updatePreferences).toHaveBeenCalledTimes(1));
    expect(updatePhoneModel).not.toHaveBeenCalled();
  });

  it('clearing the field clears the saved phone', async () => {
    updatePhoneModel.mockResolvedValue({ ...basePrefs, phone_model: null });
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await user.clear(await screen.findByLabelText('My phone'));
    await user.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updatePhoneModel).toHaveBeenCalledWith(null));
  });
});
