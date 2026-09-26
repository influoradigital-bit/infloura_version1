/**
 * U-7 UI (RULINGS-U-0917.md Round 3 §3, §7; KABIR-CONSENT-0917.md "U-7") — the "saved briefs"
 * table in Meera settings, built against a mocked `api.creatorBriefs` client. Vikram's backend
 * route lands separately; the contract here is Priya's Round 3 ruling.
 *
 * Load-bearing behaviour `tsc` cannot see:
 *   - the table renders from its OWN list call and stays visible even when the rest of the
 *     section hides on FEATURE_DISABLED (§7) — falsified by putting it back under that hide;
 *   - a repeat delete answered 404 removes the row silently, never as an error;
 *   - a 429 shows "Too many deletes, try again in a minute" and keeps the row until it resolves;
 *   - any other error keeps the row and shows a message;
 *   - a fast double-click on the confirm button sends exactly one DELETE — falsified by removing
 *     the synchronous ref guard.
 *
 * Run: npx vitest run src/components/creator/MeeraSettingsSection.saved-briefs.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { BriefListItem, CreatorAgentPreferences } from '@/lib/api';

const { getPreferences, listConversations, listBriefs, deleteBrief } = vi.hoisted(() => ({
  getPreferences: vi.fn(),
  listConversations: vi.fn(),
  listBriefs: vi.fn(),
  deleteBrief: vi.fn(),
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
      },
      creatorBriefs: {
        ...actual.api.creatorBriefs,
        list: (...args: unknown[]) => listBriefs(...args),
        delete: (...args: unknown[]) => deleteBrief(...args),
      },
    },
  };
});

import { ApiError } from '@/lib/api';
import { MeeraSettingsSection } from './MeeraSettingsSection';

const BASE_PREFS: CreatorAgentPreferences = {
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
  consent_version: 'v3',
  rate_card_shareable: false,
  rate_card: null,
  negotiation_holdout: false,
  approved_draft_count: 0,
  level_up_eligible: false,
};

function brief(overrides: Partial<BriefListItem> & Pick<BriefListItem, 'brief_id'>): BriefListItem {
  return {
    source: 'PASTED',
    status: 'ANALYZED',
    created_at: '2026-09-10T10:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  getPreferences.mockResolvedValue(BASE_PREFS);
  listConversations.mockResolvedValue({ conversations: [] });
});

describe('SavedBriefsSection — list', () => {
  it('renders every row, with source/brand/date, and the over-100 note when the cap is hit', async () => {
    const rows = Array.from({ length: 100 }, (_, i) =>
      brief({
        brief_id: `b_${i}`,
        source: i % 2 === 0 ? 'PASTED' : 'PLATFORM',
        brand_name_guess: i === 0 ? 'Glow Labs' : undefined,
      }),
    );
    listBriefs.mockResolvedValue(rows);
    render(<MeeraSettingsSection />);

    await waitFor(() => expect(listBriefs).toHaveBeenCalledWith(100));
    expect(await screen.findByText('Glow Labs')).toBeInTheDocument();
    expect(screen.getAllByText('Pasted').length).toBeGreaterThan(0);
    expect(screen.getAllByText('From a deal').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Brand not identified').length).toBeGreaterThan(0);
    expect(await screen.findByTestId('saved-briefs-cap-note')).toHaveTextContent(/latest 100/i);
  });

  it('does not show the cap note under 100 rows, and shows the empty state with zero', async () => {
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_1' })]);
    render(<MeeraSettingsSection />);
    await screen.findByTestId('saved-briefs-card');
    expect(screen.queryByTestId('saved-briefs-cap-note')).not.toBeInTheDocument();

    listBriefs.mockResolvedValue([]);
    const { findByText } = render(<MeeraSettingsSection />);
    expect(await findByText('No saved briefs yet.')).toBeInTheDocument();
  });
});

describe('SavedBriefsSection — delete', () => {
  async function renderWithOneBrief() {
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_1', brand_name_guess: 'Glow Labs' })]);
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);
    await screen.findByText('Glow Labs');
    await user.click(screen.getByRole('button', { name: 'Delete brief' }));
    expect(await screen.findByText('Delete this brief?')).toBeInTheDocument();
    return user;
  }

  it('PASTED confirm dialog body — exact text (NISHA-U7-COPY-0917.md item 1: Kabir\'s Case B scope sentence)', async () => {
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_1', source: 'PASTED', brand_name_guess: 'Glow Labs' })]);
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);
    await screen.findByText('Glow Labs');
    await user.click(screen.getByRole('button', { name: 'Delete brief' }));

    expect(
      await screen.findByText(
        'This permanently deletes this brief. Your briefs and your conversations are deleted separately. This cannot be undone.',
      ),
    ).toBeInTheDocument();
    // Not the pre-Nisha wording, and not the PLATFORM branch's text either.
    expect(screen.queryByText('This permanently deletes this brief. This cannot be undone.')).not.toBeInTheDocument();
    expect(screen.queryByText(/Meera saves a new copy automatically/)).not.toBeInTheDocument();
  });

  it('PLATFORM confirm dialog body — exact text (NISHA-U7-COPY-0917.md item 2: names Meera as the actor)', async () => {
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_2', source: 'PLATFORM', brand_name_guess: 'Nykaa' })]);
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);
    await screen.findByText('Nykaa');
    await user.click(screen.getByRole('button', { name: 'Delete brief' }));

    expect(
      await screen.findByText(
        'This deletes your saved copy of this brief. Meera saves a new copy automatically the next time she reads that deal.',
      ),
    ).toBeInTheDocument();
    // Not the pre-Nisha unattributed "Reading it again..." wording, and not the PASTED branch's text.
    expect(screen.queryByText(/^Reading it again from the deal/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Your briefs and your conversations are deleted separately/)).not.toBeInTheDocument();
  });

  it('a successful delete removes the row', async () => {
    deleteBrief.mockResolvedValue(undefined);
    const user = await renderWithOneBrief();
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deleteBrief).toHaveBeenCalledWith('b_1'));
    await waitFor(() => expect(screen.queryByText('Glow Labs')).not.toBeInTheDocument());
    expect(screen.getByText('No saved briefs yet.')).toBeInTheDocument();
  });

  it('a 404 removes the row silently — no error text anywhere', async () => {
    deleteBrief.mockRejectedValue(new ApiError('BRIEF_NOT_FOUND', 'not found', 404));
    const user = await renderWithOneBrief();
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByText('Glow Labs')).not.toBeInTheDocument());
    expect(screen.queryByText(/could not delete/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/too many deletes/i)).not.toBeInTheDocument();
  });

  it('a 429 shows "Too many deletes, try again in a minute." and keeps the row', async () => {
    // NISHA-U7-COPY-0917.md item 3 — trailing period added to match the other three error
    // strings in this file (all full sentences). This exact-match assertion is the "companion
    // fix" Nisha's review flagged as owed alongside the source change.
    deleteBrief.mockRejectedValue(new ApiError('RATE_LIMITED', 'rate limited', 429));
    const user = await renderWithOneBrief();
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Too many deletes, try again in a minute.')).toBeInTheDocument();
    expect(screen.getByText('Glow Labs')).toBeInTheDocument();
  });

  it('any other error shows a message and keeps the row', async () => {
    deleteBrief.mockRejectedValue(new ApiError('SERVER_ERROR', 'Something went wrong.', 500));
    const user = await renderWithOneBrief();
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Something went wrong.')).toBeInTheDocument();
    expect(screen.getByText('Glow Labs')).toBeInTheDocument();
  });

  it('a double-click on the confirm button sends exactly one DELETE request', async () => {
    // FINDING, not a guess: unlike "Ask Meera about this brief" (a plain Button with a real
    // async gap BEFORE any disabling state — the genuine double-click bug fixed elsewhere this
    // wave), Radix's AlertDialogAction closes the dialog on click, removing the trigger from the
    // DOM before a second click can land on it. `disabled={!!deletingId}` (kept for the
    // "Deleting…" label and to block Cancel, matching the sibling conversations table) is
    // therefore NOT what prevents the double-send here — removing it changes nothing, which is
    // asserted directly below rather than left as an unverified assumption.
    let resolveDelete: (() => void) | undefined;
    deleteBrief.mockReturnValue(new Promise<void>((resolve) => { resolveDelete = () => resolve(undefined); }));
    await renderWithOneBrief();

    const confirmButton = screen.getByRole('button', { name: 'Delete' });
    fireEvent.click(confirmButton);
    // Radix's AlertDialogAction closes the dialog on click, removing this button from the DOM
    // before a second click can land on it — confirmed by inspection, not assumed.
    expect(document.body.contains(confirmButton)).toBe(false);
    fireEvent.click(confirmButton);

    expect(deleteBrief).toHaveBeenCalledTimes(1);
    resolveDelete?.();
    await waitFor(() => expect(screen.queryByText('Glow Labs')).not.toBeInTheDocument());
  });

  it('the confirm action carries disabled={!!deletingId} in source, for the in-flight state', async () => {
    // This does NOT probe the rendered DOM node's disabled attribute: the test above already
    // proves the whole AlertDialogContent (this button included) unmounts synchronously on click,
    // in the same React commit that sets deletingId, because Radix's AlertDialogAction closes the
    // controlled dialog (onOpenChange(false) -> setDeleteTarget(null)) via the SAME click, batched
    // with our own setDeletingId(...). There is provably no render where this button is both
    // mounted/open and deletingId-truthy to assert `disabled` against — confirmed empirically
    // below, not assumed, then reverted; see the finding recorded in the source comment above
    // AlertDialogAction. So the real, observable in-flight indicator is the per-row Trash2 button
    // (`disabled={deletingId === brief.brief_id}`), asserted separately below. This test instead
    // locks the JSX SOURCE contract in place — the prop must keep matching sibling conventions and
    // must not silently regress to absent again — via a source-level regex, which is exactly the
    // thing that DID regress uncaught earlier in this task.
    const fs = await import('node:fs');
    const path = await import('node:path');
    const { fileURLToPath } = await import('node:url');
    const source = fs.readFileSync(
      path.join(path.dirname(fileURLToPath(import.meta.url)), 'MeeraSettingsSection.tsx'),
      'utf-8',
    );
    // `AlertDialogAction` also appears in the sibling "My Meera Conversations" delete dialog
    // above this component — scope the search to AFTER `function SavedBriefsSection` so a plain
    // indexOf can't silently match that first, unrelated occurrence instead of this one.
    const savedBriefsSectionStart = source.indexOf('function SavedBriefsSection');
    expect(savedBriefsSectionStart).toBeGreaterThan(-1);
    const scoped = source.slice(savedBriefsSectionStart);
    const actionBlock = scoped.slice(
      scoped.indexOf('<AlertDialogAction'),
      scoped.indexOf('</AlertDialogAction>'),
    );
    expect(actionBlock).toMatch(/disabled=\{!!deletingId\}/);
  });

  it('empirically: the confirm button never renders open+disabled simultaneously (dialog unmounts first)', async () => {
    let resolveDelete: (() => void) | undefined;
    deleteBrief.mockReturnValue(new Promise<void>((resolve) => { resolveDelete = () => resolve(undefined); }));
    await renderWithOneBrief();

    const confirmButton = screen.getByRole('button', { name: 'Delete' });
    fireEvent.click(confirmButton);
    // Proves the claim in the comment above: by the time this button could reflect
    // deletingId-truthy, it is already detached — disabled is unreachable/dead on this element.
    expect(document.body.contains(confirmButton)).toBe(false);
    resolveDelete?.();
    await waitFor(() => expect(screen.queryByText('Glow Labs')).not.toBeInTheDocument());
  });

  it('the per-row delete trigger — the ACTUAL visible in-flight indicator — is disabled while its own delete is pending', async () => {
    // Grabs the row trigger's reference BEFORE opening the confirm dialog: once open, Radix marks
    // the rest of the page aria-hidden (a real modal-focus behaviour, not a test artifact), so
    // `getByRole` can no longer find it — but the raw DOM node reference obtained beforehand still
    // reflects its own `disabled` attribute correctly, unaffected by aria-hidden elsewhere.
    let resolveDelete: (() => void) | undefined;
    deleteBrief.mockReturnValue(new Promise<void>((resolve) => { resolveDelete = () => resolve(undefined); }));
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_1', brand_name_guess: 'Glow Labs' })]);
    const user = userEvent.setup();
    render(<MeeraSettingsSection />);
    await screen.findByText('Glow Labs');

    const rowTrigger = screen.getByRole('button', { name: 'Delete brief' });
    expect(rowTrigger).not.toBeDisabled();

    await user.click(rowTrigger);
    expect(await screen.findByText('Delete this brief?')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(rowTrigger).toBeDisabled();

    resolveDelete?.();
    await waitFor(() => expect(screen.queryByText('Glow Labs')).not.toBeInTheDocument());
  });
});

describe('SavedBriefsSection — visible when the rest of the section is not (Round 3 §7)', () => {
  it('renders with FEATURE_DISABLED, when the rest of Meera settings hides', async () => {
    const { ApiError: RealApiError } = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
    getPreferences.mockRejectedValue(new RealApiError('FEATURE_DISABLED', 'off', 404));
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_1', brand_name_guess: 'Glow Labs' })]);

    render(<MeeraSettingsSection />);

    // The rest of the section (Rate Floors etc.) never appears.
    await waitFor(() => expect(listBriefs).toHaveBeenCalled());
    expect(screen.queryByText('Rate Floors')).not.toBeInTheDocument();
    // The saved-briefs table still does.
    expect(await screen.findByText('Glow Labs')).toBeInTheDocument();
    expect(screen.getByTestId('saved-briefs-card')).toBeInTheDocument();
  });

  it('renders with consent withdrawn (the rest of the section still loads, briefs are unaffected)', async () => {
    getPreferences.mockResolvedValue({ ...BASE_PREFS, consent_accepted: false });
    listBriefs.mockResolvedValue([brief({ brief_id: 'b_2', brand_name_guess: 'Nykaa' })]);

    render(<MeeraSettingsSection />);

    expect(await screen.findByText('Nykaa')).toBeInTheDocument();
    expect(screen.getByTestId('saved-briefs-card')).toBeInTheDocument();
  });
});
