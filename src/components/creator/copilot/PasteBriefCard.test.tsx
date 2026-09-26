/**
 * U-2 / U-3 (T-MEERA-CREATOR-PHASE-B SPEC.md §8.5, §8.10) — `PasteBriefCard`.
 *
 * What `tsc` cannot see, and this file pins:
 *   - the paste reaches `api.creatorBriefs.paste` and its summary, flags and quote all render;
 *   - a DEGRADED analysis says so in words, with a different sentence for a cap and an outage;
 *   - an error renders inline, never as a toast;
 *   - a dismissible flag hides for the session and comes back with "Show";
 *   - a NON-dismissible flag has no dismiss control at all — read from the flag's own field;
 *   - a response whose optional keys were OMITTED (`@JsonInclude(NON_NULL)`) renders without
 *     throwing inside a `.map()`.
 *
 * Run: npx vitest run src/components/creator/copilot/PasteBriefCard.test.tsx
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { BriefAnalysisResponse, PackageQuote, RiskFlag } from '@/lib/api';

const { pasteMock, toastMock } = vi.hoisted(() => ({
  pasteMock: vi.fn(),
  toastMock: vi.fn(),
}));

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api');
  return {
    ...actual,
    api: {
      ...actual.api,
      creatorBriefs: {
        ...actual.api.creatorBriefs,
        paste: (...args: unknown[]) => pasteMock(...args),
      },
    },
  };
});

vi.mock('@/hooks/use-toast', () => ({
  useToast: () => ({ toast: (...a: unknown[]) => toastMock(...a) }),
  toast: (...a: unknown[]) => toastMock(...a),
}));

import { ApiError } from '@/lib/api';
import { CreatorToolResultRenderer } from '@/components/creator/meera/CreatorToolResultRenderer';
import { PASTE_BRIEF_MAX_CHARS, PasteBriefCard } from './PasteBriefCard';

const BRIEF_TEXT = 'Hi! We are Glow Labs. 2 reels for our serum, budget Rs 30,000, live by 2026-10-05.';

function quote(overrides: Partial<PackageQuote> = {}): PackageQuote {
  return {
    lines: [
      {
        type: 'REEL',
        qty: 2,
        unit_price: '18,000',
        unit_price_value: 18000,
        line_total: '36,000',
        line_total_value: 36000,
        below_floor: false,
      },
    ],
    bundle_discount: '0',
    bundle_discount_value: 0,
    total: '36,000',
    total_value: 36000,
    floor_total: '30,000',
    floor_total_value: 30000,
    currency: 'INR',
    payment_schedule: '50% upfront, 50% on delivery',
    revision_rounds: 2,
    provenance: 'your last 4 priced deals',
    provenance_sample_size: 4,
    withheld: false,
    ...overrides,
  };
}

const DISMISSIBLE: RiskFlag = {
  code: 'EXCLUSIVITY_LONG',
  severity: 'WARN',
  title: 'Exclusivity runs 90 days',
  detail: 'You could not work with another skincare brand for three months.',
  action: 'Ask for 30 days.',
  data: {},
  dismissible: true,
};

const NON_DISMISSIBLE: RiskFlag = {
  code: 'HIDE_DISCLOSURE',
  severity: 'CRITICAL',
  title: 'They asked you to hide the ad label',
  detail: 'The brief asks for no #ad.',
  action: 'Keep the paid-partnership label on.',
  data: {},
  dismissible: false,
};

function analysis(overrides: Partial<BriefAnalysisResponse> = {}): BriefAnalysisResponse {
  return {
    brief_id: 'brief_01',
    source: 'PASTED',
    status: 'ANALYZED',
    extraction: {
      brand_name: 'Glow Labs',
      deliverables: [{ type: 'REEL', qty: 2 }],
      budget_inr: 30000,
      budget_stated: true,
      barter_only: false,
      deadline: '2026-10-05',
      usage_perpetual: false,
      off_platform_payment_hint: false,
      disclosure_hidden_hint: true,
      vague_deliverables: false,
    },
    flags: [DISMISSIBLE, NON_DISMISSIBLE],
    quote: quote(),
    extraction_source: 'AI',
    summary_lines: ['Glow Labs wants 2 reels for a serum.', 'Budget is ₹30,000.'],
    ...overrides,
  };
}

async function pasteAndAnalyse() {
  const user = userEvent.setup();
  render(<PasteBriefCard />);
  fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
  await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));
  return user;
}

describe('PasteBriefCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
  });

  it('sends the pasted text and renders the summary, the flags and the quote', async () => {
    pasteMock.mockResolvedValue(analysis());
    await pasteAndAnalyse();

    expect(pasteMock).toHaveBeenCalledWith(BRIEF_TEXT, expect.any(String));
    expect(await screen.findByText('Glow Labs wants 2 reels for a serum.')).toBeInTheDocument();
    expect(screen.getAllByTestId('brief-summary-line')).toHaveLength(2);

    // Extraction chips.
    const chips = within(screen.getByRole('list', { name: 'Terms found in the brief' }));
    expect(chips.getByText('2 × Reel')).toBeInTheDocument();
    expect(chips.getByText('₹30,000')).toBeInTheDocument();
    expect(chips.getByText('2026-10-05')).toBeInTheDocument();
    // Usage and exclusivity were not extracted, and say so rather than showing a blank or a 0.
    expect(chips.getAllByText('Not found')).toHaveLength(2);

    // The shared risk card, worst first.
    const rows = screen.getAllByTestId('deal-risk-row');
    expect(rows.map((r) => r.getAttribute('data-severity'))).toEqual(['CRITICAL', 'WARN']);
    expect(screen.getByText('They asked you to hide the ad label')).toBeInTheDocument();

    // The quote card.
    expect(screen.getByTestId('package-quote-card')).toBeInTheDocument();
    expect(screen.getByTestId('quote-total')).toHaveTextContent('₹36,000');

    // An AI reading carries no degraded label.
    expect(screen.queryByTestId('brief-degraded-label')).not.toBeInTheDocument();
  });

  it('says plainly when the monthly limit forced a rule-based summary', async () => {
    pasteMock.mockResolvedValue(analysis({ extraction_source: 'FALLBACK', degraded_reason: 'cap' }));
    await pasteAndAnalyse();

    const label = await screen.findByTestId('brief-degraded-label');
    expect(label).toHaveTextContent(
      "Meera's monthly limit is reached, so this summary is rule-based. Check it against the brief.",
    );
  });

  it('uses a different sentence when Meera could not be reached', async () => {
    pasteMock.mockResolvedValue(
      analysis({ extraction_source: 'FALLBACK', degraded_reason: 'ai_unavailable' }),
    );
    await pasteAndAnalyse();

    const label = await screen.findByTestId('brief-degraded-label');
    expect(label).toHaveTextContent(
      "Meera couldn't be reached just now, so this summary is rule-based. Check it against the brief.",
    );
    expect(label).not.toHaveTextContent('monthly limit');
  });

  it('renders an error inline, and never as a toast', async () => {
    pasteMock.mockRejectedValue(
      new ApiError('RATE_LIMITED', 'Too many requests. Please try again shortly.', 429),
    );
    await pasteAndAnalyse();

    const error = await screen.findByRole('alert');
    expect(error).toHaveTextContent('Too many requests. Please try again shortly.');
    expect(error).toHaveClass('text-destructive-foreground');
    expect(screen.queryByTestId('brief-card')).not.toBeInTheDocument();
    expect(toastMock).not.toHaveBeenCalled();
  });

  it('hides a dismissible flag for the session and brings it back with Show', async () => {
    pasteMock.mockResolvedValue(analysis());
    const user = await pasteAndAnalyse();

    await user.click(await screen.findByRole('button', { name: 'Dismiss Exclusivity runs 90 days' }));

    expect(screen.queryByText('Exclusivity runs 90 days')).not.toBeInTheDocument();
    expect(screen.getByTestId('deal-risk-hidden-note')).toHaveTextContent(
      '1 flag hidden for this session.',
    );
    // The non-dismissible flag is untouched.
    expect(screen.getByText('They asked you to hide the ad label')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Show hidden flags' }));
    expect(screen.getByText('Exclusivity runs 90 days')).toBeInTheDocument();
    expect(screen.queryByTestId('deal-risk-hidden-note')).not.toBeInTheDocument();
  });

  it('gives a non-dismissible flag no dismiss control at all', async () => {
    pasteMock.mockResolvedValue(analysis());
    await pasteAndAnalyse();

    await screen.findByText('They asked you to hide the ad label');
    // The dismissible flag DOES get one, so the handler really is supplied on this screen...
    expect(
      screen.getByRole('button', { name: 'Dismiss Exclusivity runs 90 days' }),
    ).toBeInTheDocument();
    // ...and the non-dismissible one gets none — not hidden, not disabled, absent.
    expect(
      screen.queryByRole('button', { name: /Dismiss They asked you to hide the ad label/ }),
    ).not.toBeInTheDocument();
    const dismissButtons = screen.queryAllByRole('button', { name: /^Dismiss / });
    expect(dismissButtons).toHaveLength(1);
  });

  // T3 (F-1779, PRIYA-LASTCALL-U2-0918.md item 6) — the risk scope really is `BRIEF:{brief_id}`,
  // not merely a truthy string. Dismissing a flag here must also hide it on the SAME brief's
  // `get_brief` tool card in Meera's chat (`CreatorToolResultRenderer`, which keys its own
  // `BriefCard` off `riskScope={`BRIEF:${payload.brief_id}`}` — CreatorToolResultRenderer.tsx
  // L744), because both read one sessionStorage-backed store (`useRiskFlagDismissals`) keyed by
  // that exact string. A missing prefix, or a different one, breaks the sharing silently — the
  // dismissal still "works" on this card alone, so nothing here would otherwise look wrong.
  it('a flag dismissed on the paste card is also hidden on the get_brief tool card for the same brief (scope BRIEF:{id})', async () => {
    pasteMock.mockResolvedValue(analysis());
    const user = await pasteAndAnalyse();

    await user.click(await screen.findByRole('button', { name: 'Dismiss Exclusivity runs 90 days' }));
    expect(screen.queryByText('Exclusivity runs 90 days')).not.toBeInTheDocument();

    // Same brief_id ('brief_01'), rendered the way `get_brief` renders in Meera's chat — scoped
    // queries below, since the paste card above is still mounted alongside it.
    const getBriefCard = render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={{
          brief_id: 'brief_01',
          source: 'PASTED',
          status: 'ANALYZED',
          extraction: analysis().extraction,
          flags: [DISMISSIBLE, NON_DISMISSIBLE],
        }}
      />,
    );

    // The dismissal made on the paste card carries over — not re-shown here either — and not
    // because this second card never received the flag: the non-dismissible one right next to it
    // does show, and both cards' own "hidden for this session" notes agree there is one hidden
    // flag each. Scoped to this render's own container — `screen`/the bound result queries both
    // default to `document.body`, which still has the first (paste) card mounted alongside this
    // one.
    const withinGetBrief = within(getBriefCard.container);
    expect(withinGetBrief.queryByText('Exclusivity runs 90 days')).not.toBeInTheDocument();
    expect(withinGetBrief.getByText('They asked you to hide the ad label')).toBeInTheDocument();
    expect(screen.getAllByTestId('deal-risk-hidden-note')).toHaveLength(2);
  });

  it('renders a response whose optional keys were omitted without throwing', async () => {
    pasteMock.mockResolvedValue({
      brief_id: 'brief_02',
      status: 'NEW',
      extraction_source: 'FALLBACK',
      degraded_reason: 'ai_unavailable',
    } satisfies BriefAnalysisResponse);
    await pasteAndAnalyse();

    expect(await screen.findByTestId('brief-card')).toBeInTheDocument();
    expect(screen.getByText('No terms could be read from this brief.')).toBeInTheDocument();
    expect(screen.getByTestId('brief-no-quote')).toBeInTheDocument();
    expect(screen.queryByTestId('deal-risk-card')).not.toBeInTheDocument();
  });

  it('counts characters and says so when a paste is cut at the 8,000 cap', () => {
    render(<PasteBriefCard />);
    const textarea = screen.getByLabelText('Brief text') as HTMLTextAreaElement;

    fireEvent.change(textarea, { target: { value: 'x'.repeat(PASTE_BRIEF_MAX_CHARS + 25) } });

    expect(textarea.value).toHaveLength(PASTE_BRIEF_MAX_CHARS);
    expect(screen.getByTestId('paste-brief-counter')).toHaveTextContent('8,000 / 8,000 characters');
    expect(screen.getByText('Only the first 8,000 characters were kept.')).toBeInTheDocument();
  });

  // T2 (F-1779, item 3b) — no `maxLength` attribute on the textarea. `fireEvent.change` above does
  // not exercise the browser's own truncation (jsdom does not implement it), so that test alone
  // cannot catch a regression that added `maxLength`: in a real browser the paste would then be cut
  // BEFORE `onChange` ever saw the rest, `truncated` would never become true, and the "Only the
  // first 8,000 characters were kept." line — the whole point of counting in `onChange` instead of
  // relying on the DOM attribute — would never show. Assert the attribute is absent directly.
  it('has no maxLength attribute on the textarea, so a long paste is trimmed by the card, not the browser', () => {
    render(<PasteBriefCard />);
    const textarea = screen.getByLabelText('Brief text') as HTMLTextAreaElement;

    expect(textarea).not.toHaveAttribute('maxlength');

    fireEvent.change(textarea, { target: { value: 'x'.repeat(PASTE_BRIEF_MAX_CHARS + 25) } });
    expect(textarea.value).toHaveLength(PASTE_BRIEF_MAX_CHARS);
    expect(screen.getByText('Only the first 8,000 characters were kept.')).toBeInTheDocument();
  });

  it('asks for consent instead of sending the paste when consent is known to be missing', async () => {
    const user = userEvent.setup();
    const onConsentRequired = vi.fn();
    render(<PasteBriefCard needsConsent onConsentRequired={onConsentRequired} />);

    fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
    await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));

    expect(onConsentRequired).toHaveBeenCalledTimes(1);
    expect(pasteMock).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Meera needs your consent'),
    );
  });

  // T1 (F-1779, item 2c) — consent UNKNOWN (`needsConsent` false, the default): the paste IS sent,
  // and a server refusal of `CONSENT_REQUIRED` is what asks for consent, not a client-side guess.
  // Without this test, replacing the whole branch with `if (false)` (so the refusal falls through
  // to the generic error path instead) is invisible — the test above only ever covers the
  // KNOWN-missing, client-side short-circuit, never the server refusal on an actual send.
  it('rejects with a server CONSENT_REQUIRED refusal (consent unknown): asks for consent, shows the message inline, and renders no brief card', async () => {
    const onConsentRequired = vi.fn();
    pasteMock.mockRejectedValue(new ApiError('CONSENT_REQUIRED', 'Consent required.', 403));
    const user = userEvent.setup();
    render(<PasteBriefCard onConsentRequired={onConsentRequired} />);

    fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
    await user.click(screen.getByRole('button', { name: 'Analyse with Meera' }));

    // The paste really was sent — this is the server's refusal, not the client short-circuit.
    expect(pasteMock).toHaveBeenCalledWith(BRIEF_TEXT, expect.any(String));
    await waitFor(() => expect(onConsentRequired).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('alert')).toHaveTextContent('Meera needs your consent');
    expect(screen.queryByTestId('brief-card')).not.toBeInTheDocument();
  });

  describe('U-5 — "Ask Meera about this brief"', () => {
    it('renders only once a brief_id has landed AND a handler is supplied', async () => {
      pasteMock.mockResolvedValue(analysis());
      const onAskMeeraAboutBrief = vi.fn();

      // No handler supplied: absent even with a result in hand.
      const noHandler = render(<PasteBriefCard />);
      fireEvent.change(noHandler.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
      await userEvent.setup().click(noHandler.getByRole('button', { name: 'Analyse with Meera' }));
      await noHandler.findByTestId('brief-card');
      expect(noHandler.queryByTestId('ask-meera-about-brief')).not.toBeInTheDocument();
      noHandler.unmount();

      // Handler supplied, but no result yet: still absent.
      const withHandler = render(<PasteBriefCard onAskMeeraAboutBrief={onAskMeeraAboutBrief} />);
      expect(withHandler.queryByTestId('ask-meera-about-brief')).not.toBeInTheDocument();

      // Both present: the button shows up, and clicking it carries the brief's own id.
      fireEvent.change(withHandler.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
      await userEvent.setup().click(withHandler.getByRole('button', { name: 'Analyse with Meera' }));
      const button = await withHandler.findByTestId('ask-meera-about-brief');
      await userEvent.setup().click(button);
      expect(onAskMeeraAboutBrief).toHaveBeenCalledWith('brief_01');
    });

    it('never promises drafting or sending, and never uses the banned payment-hold word', async () => {
      pasteMock.mockResolvedValue(analysis());
      const onAskMeeraAboutBrief = vi.fn();
      render(<PasteBriefCard onAskMeeraAboutBrief={onAskMeeraAboutBrief} />);
      fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
      await userEvent.setup().click(screen.getByRole('button', { name: 'Analyse with Meera' }));
      await screen.findByTestId('ask-meera-about-brief');

      const wording =
        (screen.getByTestId('ask-meera-about-brief').textContent ?? '') +
        (screen.getByTestId('ask-meera-about-brief-caption').textContent ?? '');
      const lower = wording.toLowerCase();
      expect(lower).not.toContain('escrow');
      // Never an unqualified promise that Meera drafts or sends on her own — only the negation.
      expect(lower).not.toMatch(/\b(will draft|drafts the|sends? (it|the reply)|sent for you)\b/);
      expect(lower).toMatch(/doesn't draft or send/);
      expect(lower).toContain('you decide');
    });

    it('renders the Hindi wording when the page language is hi-IN', async () => {
      pasteMock.mockResolvedValue(analysis());
      render(<PasteBriefCard language="hi-IN" onAskMeeraAboutBrief={vi.fn()} />);
      fireEvent.change(screen.getByLabelText('Brief text'), { target: { value: BRIEF_TEXT } });
      await userEvent.setup().click(screen.getByRole('button', { name: 'Analyse with Meera' }));
      expect(await screen.findByTestId('ask-meera-about-brief')).toHaveTextContent(
        'Meera se is brief ke baare mein poochho',
      );
    });
  });
});
