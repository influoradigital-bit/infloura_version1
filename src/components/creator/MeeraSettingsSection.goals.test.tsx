/**
 * Goal memory (Meera intelligence v1, spec §2.2/§6) — "My goals" chips on the Meera settings
 * page, next to "My phone". Each chip saves the instant it is tapped through its own route
 * (`PUT /creator/agent-preferences/content-goal`), never batched into the page's big Save
 * button, and the route replaces all four fields every call — so a single-field tap must still
 * send the other three fields' current values, not just the one that changed.
 *
 * Run: node node_modules/vitest/vitest.mjs run src/components/creator/MeeraSettingsSection.goals.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CreatorAgentPreferences } from '@/lib/api';
import { toast } from '@/hooks/use-toast';

vi.mock('@/hooks/use-toast', () => ({
  toast: vi.fn(),
  useToast: () => ({ toast: vi.fn() }),
}));

const { getPreferences, updatePreferences, updateContentGoal, listConversations } = vi.hoisted(
  () => ({
    getPreferences: vi.fn(),
    updatePreferences: vi.fn(),
    updateContentGoal: vi.fn(),
    listConversations: vi.fn(),
  }),
);

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
        updateContentGoal: (...args: unknown[]) => updateContentGoal(...args),
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
  phone_model: null,
  content_goal: null,
  weekly_time_band: null,
  equipment: [],
  content_dislikes: [],
};

describe('MeeraSettingsSection — My goals', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPreferences.mockResolvedValue(basePrefs);
    updatePreferences.mockResolvedValue(basePrefs);
    listConversations.mockResolvedValue({ conversations: [] });
  });

  it('renders unset goals with no chip selected', async () => {
    render(<MeeraSettingsSection />);
    const section = await screen.findByTestId('meera-my-goals');
    const followersChip = await screen.findByRole('button', { name: /Grow followers/ });
    expect(followersChip).toHaveAttribute('aria-pressed', 'false');
    expect(section).toBeInTheDocument();
  });

  it('renders saved values from GET as selected chips', async () => {
    getPreferences.mockResolvedValue({
      ...basePrefs,
      content_goal: 'BRAND_DEALS',
      weekly_time_band: 'H2_TO_5',
      equipment: ['TRIPOD', 'RING_LIGHT'],
      content_dislikes: ['NO_FACE'],
    });
    render(<MeeraSettingsSection />);

    expect(await screen.findByRole('button', { name: /Get brand deals/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: /2-5 hrs\/week/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: /Tripod/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Ring light/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: /^Show my face/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    // untouched single-select and the other multi options stay unset
    expect(screen.getByRole('button', { name: /Grow followers/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(screen.getByRole('button', { name: /External mic/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('tapping a single-select goal chip sends the right PUT body and reflects the response', async () => {
    updateContentGoal.mockResolvedValue({
      ...basePrefs,
      content_goal: 'GROW_FOLLOWERS',
    });
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await user.click(await screen.findByRole('button', { name: /Grow followers/ }));

    await waitFor(() =>
      expect(updateContentGoal).toHaveBeenCalledWith({
        content_goal: 'GROW_FOLLOWERS',
        weekly_time_band: null,
        equipment: [],
        content_dislikes: [],
      }),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Grow followers/ })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
    // never batched into the big PUT
    expect(updatePreferences).not.toHaveBeenCalled();
  });

  it('tapping the already-selected single-select chip clears it (sends null)', async () => {
    getPreferences.mockResolvedValue({ ...basePrefs, content_goal: 'SELL_PRODUCT' });
    updateContentGoal.mockResolvedValue({ ...basePrefs, content_goal: null });
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    const chip = await screen.findByRole('button', { name: /Sell my product/ });
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    await user.click(chip);

    await waitFor(() =>
      expect(updateContentGoal).toHaveBeenCalledWith({
        content_goal: null,
        weekly_time_band: null,
        equipment: [],
        content_dislikes: [],
      }),
    );
    await waitFor(() => expect(chip).toHaveAttribute('aria-pressed', 'false'));
  });

  it('multi-select toggling adds and removes codes, carrying the other fields along unchanged', async () => {
    getPreferences.mockResolvedValue({
      ...basePrefs,
      content_goal: 'BRAND_DEALS',
      equipment: ['PHONE_ONLY'],
    });
    updateContentGoal.mockResolvedValueOnce({
      ...basePrefs,
      content_goal: 'BRAND_DEALS',
      equipment: ['PHONE_ONLY', 'GIMBAL'],
    });
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await screen.findByRole('button', { name: /Get brand deals/ });
    await user.click(screen.getByRole('button', { name: /Gimbal/ }));

    await waitFor(() =>
      expect(updateContentGoal).toHaveBeenCalledWith({
        content_goal: 'BRAND_DEALS',
        weekly_time_band: null,
        equipment: ['PHONE_ONLY', 'GIMBAL'],
        content_dislikes: [],
      }),
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Gimbal/ })).toHaveAttribute('aria-pressed', 'true'),
    );

    // now remove PHONE_ONLY
    updateContentGoal.mockResolvedValueOnce({
      ...basePrefs,
      content_goal: 'BRAND_DEALS',
      equipment: ['GIMBAL'],
    });
    await user.click(screen.getByRole('button', { name: /Just my phone/ }));
    await waitFor(() =>
      expect(updateContentGoal).toHaveBeenLastCalledWith({
        content_goal: 'BRAND_DEALS',
        weekly_time_band: null,
        equipment: ['GIMBAL'],
        content_dislikes: [],
      }),
    );
  });

  it('shows a toast and keeps the previous selection when the save fails', async () => {
    updateContentGoal.mockRejectedValue(new Error('network down'));
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    const chip = await screen.findByRole('button', { name: /Under 2 hrs\/week/ });
    await user.click(chip);

    await waitFor(() => expect(toast).toHaveBeenCalled());
    const call = (toast as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)?.[0];
    expect(call).toMatchObject({ variant: 'destructive' });
    // rolled back to unselected, not optimistically stuck "on"
    expect(chip).toHaveAttribute('aria-pressed', 'false');
  });

  it('renders an unknown/missing saved value as unset rather than crashing', async () => {
    getPreferences.mockResolvedValue({
      ...basePrefs,
      // simulates a server-older-than-this-change payload that omits the goal-memory fields
      content_goal: undefined,
      weekly_time_band: undefined,
      equipment: undefined as unknown as string[],
      content_dislikes: undefined as unknown as string[],
    });
    render(<MeeraSettingsSection />);

    expect(await screen.findByRole('button', { name: /Grow followers/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(screen.getByRole('button', { name: /Just my phone/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('the big Save button never carries the goal-memory fields — they have their own route', async () => {
    getPreferences.mockResolvedValue({
      ...basePrefs,
      content_goal: 'BRAND_DEALS',
      weekly_time_band: 'H2_TO_5',
      equipment: ['TRIPOD'],
      content_dislikes: ['NO_FACE'],
    });
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);

    await screen.findByRole('button', { name: /Get brand deals/ });
    await user.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(updatePreferences).toHaveBeenCalledTimes(1));
    const payload = updatePreferences.mock.calls[0][0] as Record<string, unknown>;
    expect(payload).not.toHaveProperty('content_goal');
    expect(payload).not.toHaveProperty('weekly_time_band');
    expect(payload).not.toHaveProperty('equipment');
    expect(payload).not.toHaveProperty('content_dislikes');
    // and the goal route itself was never touched by the big Save button
    expect(updateContentGoal).not.toHaveBeenCalled();
  });
});
