/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.4, §8.10, §14.1.b, B0-37) — the four Phase B0 cards and the
 * tool switch.
 *
 * The load-bearing cases here are the ones `tsc` is blind to:
 *   - §14.1.b: a BENCHMARK quote shows its provenance as the card's SUBTITLE, above every figure,
 *     and labels the anchor an opening ask based on a benchmark. This is the honesty requirement
 *     the whole pricing correction exists to serve.
 *   - a WITHHELD quote hides the anchor entirely.
 *   - payloads whose optional arrays were OMITTED by `@JsonInclude(NON_NULL)` (so they arrive
 *     `undefined`, not `null`) render without throwing.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import type { BriefExtraction, PackageQuote } from '@/lib/api';
import type { AccountLast28Days, DealSummary, GetBriefPayload, MetricsResult } from '@/lib/meera-api';
import {
  BENCHMARK_PROVENANCE,
  CreatorToolResultRenderer,
  MetricsCard,
  MyDealsCard,
  NOT_AVAILABLE,
  PackageQuoteCard,
} from './CreatorToolResultRenderer';

/** Only the fields the Java record cannot omit — every optional key is added per test. */
function quote(overrides: Partial<PackageQuote> = {}): PackageQuote {
  return {
    bundle_discount: '0',
    bundle_discount_value: 0,
    total: '4,500',
    total_value: 4500,
    floor_total: '3,000',
    floor_total_value: 3000,
    currency: 'INR',
    payment_schedule: '50% upfront, 50% on delivery',
    revision_rounds: 2,
    provenance: 'your last 4 priced deals',
    provenance_sample_size: 4,
    withheld: false,
    ...overrides,
  };
}

function extraction(overrides: Partial<BriefExtraction> = {}): BriefExtraction {
  return {
    budget_stated: false,
    barter_only: false,
    usage_perpetual: false,
    off_platform_payment_hint: false,
    disclosure_hidden_hint: false,
    vague_deliverables: false,
    ...overrides,
  };
}

/** A well-formed `get_brief` payload — every field `isGetBriefPayload` requires present. */
function getBriefPayload(overrides: Partial<GetBriefPayload> = {}): GetBriefPayload {
  return {
    brief_id: 'b1',
    source: 'PASTED',
    status: 'ANALYZED',
    extraction: extraction(),
    flags: [],
    quote: quote(),
    extraction_source: 'AI',
    ...overrides,
  };
}

function deal(overrides: Partial<DealSummary> & Pick<DealSummary, 'deal_id'>): DealSummary {
  return {
    brand_name: 'Nykaa',
    campaign_title: 'Festive reels',
    status: 'NEGOTIATING',
    status_label: 'Negotiating',
    currency: 'INR',
    secured: false,
    unread_count: 0,
    has_pending_offer: true,
    ...overrides,
  };
}

describe('PackageQuoteCard — §14.1.b benchmark honesty', () => {
  it('renders benchmark provenance as the card subtitle, above every figure, not as a footnote', () => {
    render(<PackageQuoteCard quote={quote({ provenance: BENCHMARK_PROVENANCE, provenance_sample_size: 0, anchor: '4,125', anchor_value: 4125 })} />);

    const subtitle = screen.getByTestId('quote-provenance-subtitle');
    expect(subtitle).toBeInTheDocument();
    expect(subtitle).toHaveTextContent(BENCHMARK_PROVENANCE);

    // Not ALSO a footnote — the footnote variant is what §14.1.b replaced.
    expect(screen.queryByTestId('quote-provenance-footnote')).not.toBeInTheDocument();

    // "Subtitle" means it is read BEFORE any number, which is the entire point: a creator who
    // reaches the total first has already anchored on a figure we invented.
    const total = screen.getByTestId('quote-total');
    expect(
      subtitle.compareDocumentPosition(total) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('labels the anchor as an opening ask based on a benchmark', () => {
    render(<PackageQuoteCard quote={quote({ provenance: BENCHMARK_PROVENANCE, provenance_sample_size: 0, anchor: '4,125', anchor_value: 4125 })} />);
    expect(screen.getByText('Opening ask (benchmark)')).toBeInTheDocument();
    expect(
      screen.getByText('An opening ask based on a benchmark, not on closed deals.'),
    ).toBeInTheDocument();
  });

  it('does NOT treat the shrinkage provenance as benchmark mode', () => {
    // §14.1.c — "blended with benchmark" contains the word but is built on the creator's own
    // closes. Substring matching here would understate data we really do have.
    render(
      <PackageQuoteCard
        quote={quote({ provenance: 'your last 2 priced deals, blended with benchmark', provenance_sample_size: 2, anchor: '5,175', anchor_value: 5175 })}
      />,
    );
    expect(screen.queryByTestId('quote-provenance-subtitle')).not.toBeInTheDocument();
    expect(screen.getByTestId('quote-provenance-footnote')).toBeInTheDocument();
    expect(screen.getByText('Opening ask')).toBeInTheDocument();
  });
});

describe('PackageQuoteCard — withheld and absent fields', () => {
  it('hides the anchor entirely when the quote says it is withheld', () => {
    // The anchor is deliberately PRESENT in this payload. Without it the assertion below would
    // pass for the wrong reason — an absent anchor renders nothing regardless of `withheld`.
    render(
      <PackageQuoteCard
        quote={quote({
          anchor: '5,175',
          anchor_value: 5175,
          withheld: true,
          withheld_reason: 'Meera is holding back a number on this one.',
        })}
      />,
    );
    expect(screen.queryByTestId('quote-anchor')).not.toBeInTheDocument();
    expect(screen.queryByText(/Opening ask/)).not.toBeInTheDocument();
    expect(screen.getByTestId('quote-withheld')).toHaveTextContent(
      'Meera is holding back a number on this one.',
    );
    // …and no "Use in counter" on a quote with no number to carry.
    expect(screen.queryByTestId('quote-use-in-counter')).not.toBeInTheDocument();
  });

  it('hides the anchor when anchor/anchor_value were omitted (holdout), even with withheld false', () => {
    render(<PackageQuoteCard quote={quote()} />);
    expect(screen.queryByTestId('quote-anchor')).not.toBeInTheDocument();
    expect(screen.getByTestId('quote-total')).toHaveTextContent('₹4,500');
  });

  it('renders with lines and add_ons OMITTED without throwing', () => {
    // `lines`/`add_ons` are List<...> on a @JsonInclude(NON_NULL) record: absent, not null. A
    // bare `.map()` would throw here and `tsc` would never have seen it.
    const bare = quote();
    expect(bare.lines).toBeUndefined();
    expect(bare.add_ons).toBeUndefined();
    expect(() => render(<PackageQuoteCard quote={bare} />)).not.toThrow();
    expect(screen.queryByTestId('quote-line')).not.toBeInTheDocument();
  });

  it('renders lines, the below-floor marker and add-ons when present', () => {
    render(
      <PackageQuoteCard
        quote={quote({
          lines: [
            { type: 'REEL', qty: 1, unit_price: '4,500', unit_price_value: 4500, line_total: '4,500', line_total_value: 4500, below_floor: true },
          ],
          add_ons: [{ code: 'WHITELISTING', label: 'Whitelisting', amount: '3,375', amount_value: 3375, basis: '75% of package' }],
          bundle_discount: '500',
          bundle_discount_value: 500,
        })}
      />,
    );
    expect(screen.getByText(/1 × Reel/)).toBeInTheDocument();
    const marker = screen.getByTestId('quote-below-floor');
    expect(marker).toHaveClass('bg-destructive');
    expect(marker).toHaveClass('text-destructive-foreground');
    expect(screen.getByText('Whitelisting')).toBeInTheDocument();
    expect(screen.getByText('− ₹500')).toBeInTheDocument();
  });

  it('"Use in counter" prefers the anchor and falls back to the total', async () => {
    const onPrefillCounter = vi.fn();
    const { unmount } = render(
      <PackageQuoteCard quote={quote({ anchor: '5,175', anchor_value: 5175 })} onPrefillCounter={onPrefillCounter} />,
    );
    await userEvent.click(screen.getByTestId('quote-use-in-counter'));
    expect(onPrefillCounter).toHaveBeenCalledWith({ amount: 5175 });

    unmount();
    render(<PackageQuoteCard quote={quote()} onPrefillCounter={onPrefillCounter} />);
    await userEvent.click(screen.getByTestId('quote-use-in-counter'));
    expect(onPrefillCounter).toHaveBeenLastCalledWith({ amount: 4500 });
  });
});

describe('MetricsCard', () => {
  it('falls back to "Not available yet" — never a zero or a dash — for every absent string', () => {
    const metrics: MetricsResult = { connected: false, tier: 'NANO' };
    render(<MetricsCard metrics={metrics} />);

    // The five strings §8.4 names MINUS "Reach (30 days)" (removed — see MetricsCard's own doc
    // comment), all omitted by the executor when there is no metric row.
    expect(screen.getAllByTestId('metrics-missing')).toHaveLength(5);
    expect(screen.getAllByText(NOT_AVAILABLE)).toHaveLength(5);
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    expect(screen.queryByText('—')).not.toBeInTheDocument();
    expect(screen.getByText(/Tier NANO/)).toBeInTheDocument();
  });

  it('renders the real strings when the account is connected', () => {
    const metrics: MetricsResult = {
      connected: true,
      followers: '3,412',
      reach_30d: '48,900',
      engagement_rate: '2.4%',
      avg_reach_per_post: '1,120',
      verified_at: '5 Oct 2026',
      quality_score: '71',
      tier: 'NANO',
      data_source: 'INSTAGRAM',
    };
    render(<MetricsCard metrics={metrics} />);
    expect(screen.queryByTestId('metrics-missing')).not.toBeInTheDocument();
    expect(screen.getByText('2.4%')).toBeInTheDocument();
    expect(screen.getByText(/Source: INSTAGRAM/)).toBeInTheDocument();
  });

  it('never renders a "Reach (30 days)" row — reach_30d is undefined by design, no 30-day total exists', () => {
    const metrics: MetricsResult = {
      connected: true,
      followers: '3,412',
      reach_30d: '48,900', // still on the wire (Java field exists); the card must not show it
      engagement_rate: '2.4%',
    };
    render(<MetricsCard metrics={metrics} />);
    expect(screen.queryByText(/Reach \(30 days\)/)).not.toBeInTheDocument();
    expect(screen.queryByText('48,900')).not.toBeInTheDocument();
  });

  it('shows a "Last 28 days" group when accountLast28Days is available, hiding a null figure', () => {
    const last28: AccountLast28Days = {
      available: true,
      period: '27 Aug - 23 Sep 2026',
      accounts_reached: '12,480',
      views: '31,900',
      interactions: null,
      accounts_engaged: '0',
      profile_link_taps: undefined,
    };
    const metrics: MetricsResult = { connected: true, followers: '3,412' };
    render(<MetricsCard metrics={metrics} accountLast28Days={last28} />);

    const group = screen.getByTestId('metrics-last-28-days');
    expect(group).toHaveTextContent('Last 28 days (27 Aug - 23 Sep 2026)');
    expect(group).toHaveTextContent('12,480');
    expect(group).toHaveTextContent('31,900');
    // Interactions is null and Profile link taps is undefined — both hidden, not "Not available yet".
    expect(screen.queryByText('Interactions')).not.toBeInTheDocument();
    expect(screen.queryByText('Profile link taps')).not.toBeInTheDocument();
    // Accounts engaged is the STRING "0", a real reported value — shown, not hidden.
    expect(group).toHaveTextContent('Accounts engaged');
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('renders nothing extra when accountLast28Days.available is false, or the prop is absent', () => {
    const metrics: MetricsResult = { connected: true, followers: '3,412' };
    const { rerender } = render(
      <MetricsCard metrics={metrics} accountLast28Days={{ available: false }} />,
    );
    expect(screen.queryByTestId('metrics-last-28-days')).not.toBeInTheDocument();
    expect(screen.queryByText('Last 28 days')).not.toBeInTheDocument();

    rerender(<MetricsCard metrics={metrics} />);
    expect(screen.queryByTestId('metrics-last-28-days')).not.toBeInTheDocument();
  });
});

describe('MyDealsCard', () => {
  it('renders brand, status pill, amount, next action and the secured badge', () => {
    render(
      <MyDealsCard
        activeCount={1}
        completedCount={2}
        deals={[
          deal({ deal_id: 'd1', amount: '8,000', amount_value: 8000, next_action: 'reply to brand', secured: true }),
        ]}
      />,
    );
    expect(screen.getByText('Nykaa')).toBeInTheDocument();
    expect(screen.getByTestId('my-deals-status')).toHaveTextContent('Negotiating');
    expect(screen.getByText('₹8,000')).toBeInTheDocument();
    expect(screen.getByText('reply to brand')).toBeInTheDocument();
    expect(screen.getByTestId('my-deals-secured')).toBeInTheDocument();
    expect(screen.getByText('1 active · 2 completed')).toBeInTheDocument();
  });

  it('says so honestly when amount and next_action were omitted, and hides the secured badge', () => {
    render(<MyDealsCard deals={[deal({ deal_id: 'd2' })]} />);
    expect(screen.getByText('Not set yet')).toBeInTheDocument();
    expect(screen.queryByText('Next')).not.toBeInTheDocument();
    expect(screen.queryByTestId('my-deals-secured')).not.toBeInTheDocument();
  });

  it('renders with the deals array absent without throwing', () => {
    expect(() => render(<MyDealsCard />)).not.toThrow();
    expect(screen.getByText('No deals to show yet.')).toBeInTheDocument();
  });
});

describe('CreatorToolResultRenderer', () => {
  it('renders nothing for a tool name outside CREATOR_TOOL_NAMES', () => {
    const { container } = render(
      <CreatorToolResultRenderer toolName="create_campaign" status="ok" data={{ deals: [] }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('picks the right card for each of the four B0 payloads it can render', () => {
    const deals = render(
      <CreatorToolResultRenderer
        toolName="get_my_deals"
        status="ok"
        data={{ deals: [deal({ deal_id: 'd1' })], active_count: 1, completed_count: 0 }}
      />,
    );
    expect(deals.getByTestId('my-deals-card')).toBeInTheDocument();

    const metrics = render(
      <CreatorToolResultRenderer
        toolName="get_my_metrics"
        status="ok"
        data={{ metrics: { connected: false, tier: 'NANO' } }}
      />,
    );
    expect(metrics.getByTestId('metrics-card')).toBeInTheDocument();

    const rate = render(
      <CreatorToolResultRenderer toolName="estimate_my_rate" status="ok" data={{ quote: quote() }} />,
    );
    expect(rate.getByTestId('package-quote-card')).toBeInTheDocument();

    const risks = render(
      <CreatorToolResultRenderer
        toolName="check_deal_risks"
        status="ok"
        data={{
          flags: [
            { code: 'BELOW_FLOOR', severity: 'CRITICAL', title: 'Under your floor', detail: 'd', action: 'a', data: {}, dismissible: false },
          ],
          highest_severity: 'CRITICAL',
          target: 'DEAL',
          target_id: 'd1',
        }}
      />,
    );
    expect(risks.getByTestId('deal-risk-card')).toBeInTheDocument();
  });

  it('U-3: the check_deal_risks card supplies dismiss for dismissible flags only, scoped to its target', async () => {
    window.sessionStorage.clear();
    const user = userEvent.setup();
    render(
      <CreatorToolResultRenderer
        toolName="check_deal_risks"
        status="ok"
        data={{
          flags: [
            { code: 'OFF_PLATFORM_PAYMENT', severity: 'CRITICAL', title: 'Pay outside Influora', detail: 'd', action: 'a', data: {}, dismissible: false },
            { code: 'USAGE_LONG', severity: 'WARN', title: 'Usage runs a year', detail: 'd', action: 'a', data: {}, dismissible: true },
          ],
          highest_severity: 'CRITICAL',
          target: 'DEAL',
          target_id: 'd9',
        }}
      />,
    );

    expect(screen.queryByRole('button', { name: /Dismiss Pay outside Influora/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Dismiss Usage runs a year' }));

    expect(screen.queryByText('Usage runs a year')).not.toBeInTheDocument();
    expect(screen.getByText('Pay outside Influora')).toBeInTheDocument();
    expect(window.sessionStorage.getItem('influora.riskFlagDismissals.v1')).toBe(
      JSON.stringify({ 'DEAL:d9': ['USAGE_LONG'] }),
    );
    window.sessionStorage.clear();
  });

  it('U-3: never hides a non-dismissible flag, even when its code is already in the session store', () => {
    // A rule that became non-dismissible after a creator hid it, or a hand-edited store: the
    // flag's own `dismissible: false` wins over anything stored.
    window.sessionStorage.setItem(
      'influora.riskFlagDismissals.v1',
      JSON.stringify({ 'BRIEF:b7': ['REGULATED_CATEGORY'] }),
    );
    render(
      <CreatorToolResultRenderer
        toolName="check_deal_risks"
        status="ok"
        data={{
          flags: [
            { code: 'REGULATED_CATEGORY', severity: 'CRITICAL', title: 'Regulated category', detail: 'd', action: 'a', data: {}, dismissible: false },
          ],
          highest_severity: 'CRITICAL',
          target: 'BRIEF',
          target_id: 'b7',
        }}
      />,
    );
    expect(screen.getByText('Regulated category')).toBeInTheDocument();
    expect(screen.queryByTestId('deal-risk-hidden-note')).not.toBeInTheDocument();
    window.sessionStorage.clear();
  });

  it('U-3 UF3-2 (PRIYA-LASTCALL-U3-U5-0917.md item 4): no target/target_id means no scope, and no dismiss button — never one shared bucket', () => {
    // Every check_deal_risks payload elsewhere in this file carries a target/target_id. This is
    // the one case that omits both, which is what a malformed or partial tool result looks like —
    // the card must still render the flags, just with no dismiss control, rather than falling
    // into one shared "UNSCOPED" bucket that would hide a flag on an UNRELATED deal/brief.
    render(
      <CreatorToolResultRenderer
        toolName="check_deal_risks"
        status="ok"
        data={{
          flags: [
            { code: 'EXCLUSIVITY_LONG', severity: 'WARN', title: 'Exclusivity runs long', detail: 'd', action: 'a', data: {}, dismissible: true },
          ],
          highest_severity: 'WARN',
        }}
      />,
    );
    expect(screen.getByText('Exclusivity runs long')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Dismiss / })).not.toBeInTheDocument();
  });

  it('renders nothing for draft_reply, the one B0 tool whose card lands in a later wave', () => {
    const draft = render(
      <CreatorToolResultRenderer toolName="draft_reply" status="ok" data={{ draft_id: 'x', kind: 'REPLY' }} />,
    );
    expect(draft.container).toBeEmptyDOMElement();
  });

  it('renders nothing, and does not throw, on a payload that fails its guard', () => {
    const { container } = render(
      <CreatorToolResultRenderer toolName="get_my_deals" status="ok" data={{ nonsense: true }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders an error on the destructive surface with destructive-foreground text', () => {
    render(
      <CreatorToolResultRenderer toolName="estimate_my_rate" status="error" errorMessage="Rate service is down." />,
    );
    const error = screen.getByTestId('creator-tool-error');
    expect(error).toHaveTextContent('Rate service is down.');
    expect(error).toHaveClass('bg-destructive');
    expect(error).toHaveClass('text-destructive-foreground');
  });
});

describe('CreatorToolResultRenderer — get_brief (U-4)', () => {
  it('renders the full BriefCard — summary chips, risk flags and the quote — for an analyzed brief', () => {
    render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={getBriefPayload({
          status: 'ANALYZED',
          extraction: extraction({ budget_inr: 5000, deliverables: [{ type: 'REEL', qty: 1 }] }),
          flags: [
            { code: 'USAGE_PERPETUAL', severity: 'CRITICAL', title: 'Usage runs forever', detail: 'd', action: 'a', data: {}, dismissible: false },
          ],
        })}
      />,
    );
    expect(screen.getByTestId('brief-card')).toBeInTheDocument();
    expect(screen.getByText('₹5,000')).toBeInTheDocument();
    expect(screen.getByTestId('deal-risk-card')).toBeInTheDocument();
    expect(screen.getByText('Usage runs forever')).toBeInTheDocument();
    expect(screen.getByTestId('package-quote-card')).toBeInTheDocument();
  });

  it('says the summary was read without AI when extraction_source is FALLBACK', () => {
    render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={getBriefPayload({ status: 'ANALYZED', extraction_source: 'FALLBACK' })}
      />,
    );
    expect(screen.getByTestId('brief-degraded-label')).toHaveTextContent(
      'This summary is rule-based, not read by Meera.',
    );
  });

  it('renders a plain "still reading" state for a NEW brief, never a clean card with zero flags', () => {
    render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={getBriefPayload({ status: 'NEW', flags: [], quote: quote({ withheld: true }) })}
      />,
    );
    expect(screen.getByTestId('get-brief-still-reading')).toBeInTheDocument();
    // Not the full card in any of its parts — a NEW brief has nothing analyzed yet, so none of
    // BriefCard's pieces (which would otherwise read as "checked, and clean") may appear.
    expect(screen.queryByTestId('brief-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('deal-risk-card')).not.toBeInTheDocument();
    expect(screen.queryByTestId('package-quote-card')).not.toBeInTheDocument();
    expect(screen.queryByText(/no risk/i)).not.toBeInTheDocument();
  });

  it('rejects a malformed payload instead of rendering the old "undefined revisions" garbage', () => {
    // The prior guard only checked brief_id + extraction. This shape would have passed it, and a
    // card built on it read "Not available yet · undefined revisions · Based on .".
    const { container } = render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={{ brief_id: 'b1', extraction: {} }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByText(/undefined revision/)).not.toBeInTheDocument();
  });

  it('rejects a payload with a non-array flags field', () => {
    const raw = getBriefPayload();
    const { container } = render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={{ ...raw, flags: undefined }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('rejects a payload with an unrecognised status', () => {
    const raw = getBriefPayload();
    const { container } = render(
      <CreatorToolResultRenderer
        toolName="get_brief"
        status="ok"
        data={{ ...raw, status: 'BOGUS' }}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('CreatorToolResultRenderer — get_brief F6 round 2 (KAVYA-FE-RECHECK-0917.md)', () => {
  it('renders a real pasted brief the way Java actually serialises it — deal_id, quote and extraction_source all OMITTED, not null', () => {
    // Object literal with the three keys left out entirely (not set to undefined) — this is what
    // `JSON.parse` produces for an `@JsonInclude(NON_NULL)` field the backend has nothing for.
    const full = getBriefPayload({ status: 'ANALYZED' });
    const { deal_id: _deal_id, quote: _quote, extraction_source: _extraction_source, ...pastedNoPrice } = full;
    expect('deal_id' in pastedNoPrice).toBe(false);
    expect('quote' in pastedNoPrice).toBe(false);
    expect('extraction_source' in pastedNoPrice).toBe(false);

    render(<CreatorToolResultRenderer toolName="get_brief" status="ok" data={pastedNoPrice} />);

    // The card still renders — an absent quote/extraction_source is not a malformed payload.
    expect(screen.getByTestId('brief-card')).toBeInTheDocument();
    // Honest "no price" state, reusing BriefCard's own existing fallback — never "undefined", and
    // never a quote card with nothing in it.
    expect(screen.getByTestId('brief-no-quote')).toHaveTextContent('No price suggestion for this brief.');
    expect(screen.queryByTestId('package-quote-card')).not.toBeInTheDocument();
    // Absent extraction_source is treated as unknown, not as FALLBACK — no degraded label either.
    expect(screen.queryByTestId('brief-degraded-label')).not.toBeInTheDocument();
  });

  it('a bare NEW stub with no extraction/flags/quote at all still reaches "still reading" (defence in depth)', () => {
    // What Addition A's 409 is supposed to make impossible — kept as a guard against a stale
    // build or a future regression reintroducing it.
    render(
      <CreatorToolResultRenderer toolName="get_brief" status="ok" data={{ brief_id: 'brief_stub', status: 'NEW' }} />,
    );
    expect(screen.getByTestId('get-brief-still-reading')).toBeInTheDocument();
    expect(screen.queryByTestId('brief-card')).not.toBeInTheDocument();
  });

  it('still rejects a PRESENT but malformed quote (not an object)', () => {
    const raw = getBriefPayload();
    const { container } = render(
      <CreatorToolResultRenderer toolName="get_brief" status="ok" data={{ ...raw, quote: 'nope' }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('still rejects a PRESENT but malformed quote (an array, not the PackageQuote object)', () => {
    const raw = getBriefPayload();
    const { container } = render(
      <CreatorToolResultRenderer toolName="get_brief" status="ok" data={{ ...raw, quote: [] }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('still rejects a PRESENT but unrecognised extraction_source', () => {
    const raw = getBriefPayload();
    const { container } = render(
      <CreatorToolResultRenderer toolName="get_brief" status="ok" data={{ ...raw, extraction_source: 'BOGUS' }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
