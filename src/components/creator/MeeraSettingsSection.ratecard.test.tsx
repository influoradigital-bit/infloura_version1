/**
 * T-MEERA-CREATOR-PHASE-B (§8.1, QA Wave 1 finding 3) — `toDraft()` in `MeeraSettingsSection.tsx`
 * destructures five server-owned fields out of `CreatorAgentPreferences` before it becomes the PUT
 * draft: `consent_accepted`/`consent_version` (pre-existing, Phase A) and, as of Phase B,
 * `negotiation_holdout`/`approved_draft_count`/`level_up_eligible` — `UpdatePreferencesRequest` on
 * the Java side has no field for any of the five (SPEC.md §8.1). This file is what the component's
 * own comment cites for that guarantee; it previously did not exist, so the three new omissions
 * were untested.
 *
 * `rate_card_shareable`/`rate_card` are the opposite case — creator-editable, so they MUST survive
 * into the PUT payload untouched.
 *
 * Run: npx vitest run src/components/creator/MeeraSettingsSection.ratecard.test.tsx
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

// Server-owned fields deliberately set to non-default, truthy values: if `toDraft()` ever leaks
// one into the draft/payload, asserting mere key-presence below still catches it regardless of
// the value the leaked field happens to carry.
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
  rate_card_shareable: true,
  rate_card: { reel: '8,000', story_set: '5,000', post: '3,000' },
  negotiation_holdout: true,
  approved_draft_count: 12,
  level_up_eligible: true,
  equipment: [],
  content_dislikes: [],
};

describe('MeeraSettingsSection — PUT payload carries rate_card, never the server-owned Phase B fields', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPreferences.mockResolvedValue(basePrefs);
    updatePreferences.mockResolvedValue(basePrefs);
    listConversations.mockResolvedValue({ conversations: [] });
  });

  it('sends rate_card_shareable and rate_card, and never negotiation_holdout, approved_draft_count or level_up_eligible', async () => {
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(screen.getByText('Rate Floors')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /save meera settings/i }));

    await waitFor(() => expect(updatePreferences).toHaveBeenCalledTimes(1));
    const payload = updatePreferences.mock.calls[0][0] as Record<string, unknown>;

    // Creator-editable — must round-trip.
    expect(payload).toHaveProperty('rate_card_shareable', true);
    expect(payload).toHaveProperty('rate_card', { reel: '8,000', story_set: '5,000', post: '3,000' });

    // Server-owned (T-MEERA-CREATOR-PHASE-B §8.1) — UpdatePreferencesRequest on the Java side has
    // no field for any of these three; the PUT must never carry them.
    expect(payload).not.toHaveProperty('negotiation_holdout');
    expect(payload).not.toHaveProperty('approved_draft_count');
    expect(payload).not.toHaveProperty('level_up_eligible');

    // Pre-existing Phase A guarantee, still held: consent fields are also server-owned.
    expect(payload).not.toHaveProperty('consent_accepted');
    expect(payload).not.toHaveProperty('consent_version');
  });
});
