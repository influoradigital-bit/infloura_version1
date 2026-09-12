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
import type { PackageQuote } from '@/lib/api';
import type { DealSummary, MetricsResult } from '@/lib/meera-api';
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

    // The six strings §8.4 names, all omitted by the executor when there is no metric row.
    expect(screen.getAllByTestId('metrics-missing')).toHaveLength(6);
    expect(screen.getAllByText(NOT_AVAILABLE)).toHaveLength(6);
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

  it('renders nothing for the two B0 tools whose cards land in later waves', () => {
    const brief = render(
      <CreatorToolResultRenderer toolName="get_brief" status="ok" data={{ brief_id: 'b1', extraction: {} }} />,
    );
    expect(brief.container).toBeEmptyDOMElement();

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
