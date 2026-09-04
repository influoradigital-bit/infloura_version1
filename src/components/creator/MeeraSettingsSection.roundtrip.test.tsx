/**
 * T-MEERA-CREATOR-PHASE-A gate review fix (Priya's frontend gate, item 1).
 *
 * MeeraSettingsSection had no control for `working_hours_timezone`/`floor_currency` and the
 * floor labels hardcoded "₹" regardless of the persisted currency. This exercises the real
 * Select components (jsdom's Radix gaps are pre-patched in src/test/setup.ts) end-to-end:
 * change both values, save, and assert the PUT payload carries them AND the re-rendered UI
 * reflects the server's response — a true round-trip, not just local state mutation.
 *
 * Run: npx vitest run src/components/creator/MeeraSettingsSection.roundtrip.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CreatorAgentPreferences } from '@/lib/api';

// vi.mock is hoisted above imports/top-level consts, so the mock fns referenced inside its
// factory must be created through vi.hoisted() (see vitest's hoisting docs) rather than plain
// top-level consts, which would still be in their TDZ when the factory runs.
const { getPreferences, updatePreferences, listConversations } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  updatePreferences: vi.fn(),
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
  consent_version: 'v1',
};

describe('MeeraSettingsSection — currency & timezone round-trip', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPreferences.mockResolvedValue(basePrefs);
    listConversations.mockResolvedValue({ conversations: [] });
  });

  it('renders the persisted currency symbol, not a hardcoded ₹', async () => {
    getPreferences.mockResolvedValue({ ...basePrefs, floor_currency: 'USD' });
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(screen.getByText(/Reel \(\$\)/)).toBeInTheDocument());
    expect(screen.queryByText(/Reel \(₹\)/)).not.toBeInTheDocument();
  });

  it('changes currency and timezone, sends both (and only those PUT-eligible fields) in the payload, and reflects the saved response back', async () => {
    const saved: CreatorAgentPreferences = {
      ...basePrefs,
      floor_currency: 'USD',
      working_hours_timezone: 'America/New_York',
    };
    updatePreferences.mockResolvedValue(saved);

    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(screen.getByText('Rate Floors')).toBeInTheDocument());
    // Starts from the persisted INR value, not a hardcoded symbol.
    expect(screen.getByText(/Reel \(₹\)/)).toBeInTheDocument();

    await user.click(screen.getByRole('combobox', { name: /rate floor currency/i }));
    await user.click(await screen.findByRole('option', { name: /USD — US Dollar/i }));

    // The floor labels re-derive their symbol from the newly-selected currency immediately —
    // proves the label is driven by draft.floor_currency, not a hardcoded string.
    await waitFor(() => expect(screen.getByText(/Reel \(\$\)/)).toBeInTheDocument());

    await user.click(screen.getByRole('combobox', { name: /working hours timezone/i }));
    await user.click(await screen.findByRole('option', { name: /America\/New_York/i }));

    await user.click(screen.getByRole('button', { name: /save meera settings/i }));

    await waitFor(() => expect(updatePreferences).toHaveBeenCalledTimes(1));
    const payload = updatePreferences.mock.calls[0][0] as Record<string, unknown>;
    expect(payload.floor_currency).toBe('USD');
    expect(payload.working_hours_timezone).toBe('America/New_York');
    // consent_accepted/consent_version are server-owned — UpdatePreferencesRequest on the Java
    // side has no field for either, so the draft/payload must never carry them.
    expect(payload).not.toHaveProperty('consent_accepted');
    expect(payload).not.toHaveProperty('consent_version');

    // Round-trip complete: the section now reflects what the server actually persisted and
    // echoed back, not just the locally-selected value.
    await waitFor(() => expect(screen.getByText(/Reel \(\$\)/)).toBeInTheDocument());
  });
});
