import * as React from 'react';
import { cn } from '@/lib/utils';
import { DealRiskCard } from '@/components/shared/deal-risk-card';
import type { AddOnLine, PackageQuote, QuoteDeliverableType, QuoteLine } from '@/lib/api';
import type { DealTerms } from '@/lib/types';
import {
  isCheckDealRisksPayload,
  isCreatorToolName,
  isEstimateMyRatePayload,
  isGetMyDealsPayload,
  isGetMyMetricsPayload,
  type DealSummary,
  type MetricsResult,
} from '@/lib/meera-api';

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.4, B0-37) — the creator-side card library for Meera's tool
 * results, plus the switch that picks a card for a tool name.
 *
 * ## Which cards exist here
 * Phase B0 ships six creator tools (§14.5.a). Four of them can be rendered with what B0 has
 * actually built, and those four cards are here: `MyDealsCard` (`get_my_deals`), `MetricsCard`
 * (`get_my_metrics`), `PackageQuoteCard` (`estimate_my_rate`) and `DealRiskCard`
 * (`check_deal_risks`, imported from `components/shared/` because the deal pages use it too,
 * §8.6). `BriefCard` (`get_brief`) lands with the paste surface in B0-44 and `DraftCard`
 * (`draft_reply`) with the approval flow in B0-49; until then those two tool names render
 * nothing rather than a placeholder that claims a feature exists.
 *
 * ## Styling
 * Creator tokens only — `border-border`, `bg-card`, `bg-muted` — never the brand `meera-*`
 * family. All tokens are declared in the Tailwind v4 `@theme inline` block of
 * `src/app/globals.css`; there is no `tailwind.config` in this repo.
 *
 * ## Absent, not null
 * Every DTO behind these payloads is `@JsonInclude(NON_NULL)`, so a field the server has nothing
 * for is OMITTED and arrives `undefined`. Two consequences are load-bearing throughout this file
 * and neither is visible to `tsc`:
 *   1. `.map()` is never called on an optional array (`quote.lines`, `quote.add_ons`,
 *      `risks.flags`) without a presence check first — it would throw at runtime.
 *   2. Nothing is compared to `null`. A `=== null` test against a key that was never sent is a
 *      branch that can never fire, and it fails silently.
 */

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

/** The honest fallback for a metric the platform genuinely does not have. Never "0", never "—". */
export const NOT_AVAILABLE = 'Not available yet';

/**
 * `RateAddOns.PROVENANCE_BENCHMARK` — the exact string §4.3 step 1c stamps on a constants-derived
 * unit price, and the string §14.1.b keys the honesty treatment off.
 *
 * Matched EXACTLY, not by substring. `"your last 2 priced deals, blended with benchmark"`
 * (§14.1.c shrinkage) also contains the word "benchmark", but it is a quote with the creator's
 * own closes in it — treating it as pure benchmark would understate data we really do have.
 */
export const BENCHMARK_PROVENANCE = 'benchmark, not market data';

export function isBenchmarkQuote(quote: PackageQuote): boolean {
  return quote.provenance === BENCHMARK_PROVENANCE;
}

/**
 * The backend already renders every money figure as a locale-formatted string (`Rendered.money`,
 * §4.3 step 9) and sends the currency alongside it, so this only adds the symbol. It deliberately
 * does not re-format `*_value`: doing that would hard-code `en-IN` on the client and silently
 * relabel a non-INR quote as rupees.
 */
function money(rendered: string | undefined, currency: string): string {
  if (!rendered) return NOT_AVAILABLE;
  return currency === 'INR' ? `₹${rendered}` : `${currency} ${rendered}`;
}

const DELIVERABLE_LABELS: Record<QuoteDeliverableType, string> = {
  REEL: 'Reel',
  STATIC_POST: 'Static post',
  STORY_SET: 'Story set',
  SHORT: 'Short',
  YT_INTEGRATION: 'YouTube integration',
  YT_DEDICATED: 'YouTube dedicated video',
  UGC_ONLY: 'UGC only (no posting)',
  OTHER: 'Other',
};

function deliverableLabel(type: string): string {
  return DELIVERABLE_LABELS[type as QuoteDeliverableType] ?? type;
}

function CardShell({
  title,
  subtitle,
  children,
  className,
  testId,
}: {
  title: React.ReactNode;
  subtitle?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  testId?: string;
}) {
  return (
    <div
      data-testid={testId}
      className={cn('rounded-xl border border-border bg-card p-3 space-y-3', className)}
    >
      <div className="space-y-1">
        <p className="text-sm font-semibold">{title}</p>
        {subtitle}
      </div>
      {children}
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-3 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right break-words">{value}</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// MyDealsCard — get_my_deals
// ---------------------------------------------------------------------------

export interface MyDealsCardProps {
  deals?: DealSummary[];
  activeCount?: number;
  completedCount?: number;
  onOpenDeal?: (dealId: string) => void;
  className?: string;
}

export function MyDealsCard({
  deals,
  activeCount,
  completedCount,
  onOpenDeal,
  className,
}: MyDealsCardProps) {
  const rows = deals ?? [];

  return (
    <CardShell
      testId="my-deals-card"
      className={className}
      title="Your deals"
      subtitle={
        activeCount === undefined && completedCount === undefined ? undefined : (
          <p className="text-xs text-muted-foreground">
            {activeCount ?? 0} active · {completedCount ?? 0} completed
          </p>
        )
      }
    >
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">No deals to show yet.</p>
      ) : (
        <ul className="space-y-2">
          {rows.map((deal) => (
            <li
              key={deal.deal_id}
              data-testid="my-deals-row"
              className="rounded-lg border border-border bg-muted/30 p-2.5 space-y-1.5"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium break-words">{deal.brand_name}</p>
                  <p className="text-xs text-muted-foreground break-words">
                    {deal.campaign_title}
                  </p>
                </div>
                <span
                  data-testid="my-deals-status"
                  className="shrink-0 rounded-md bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground"
                >
                  {deal.status_label}
                </span>
              </div>

              {/* `amount`/`amount_value` come from one nullable BigDecimal and are omitted
                  together. An offer with no figure on the table yet says so. */}
              <Row
                label="Amount"
                value={
                  deal.amount
                    ? money(deal.amount, deal.currency)
                    : <span className="text-muted-foreground">Not set yet</span>
                }
              />

              {/* Omitted when there is nothing for the creator to do. */}
              {deal.next_action ? <Row label="Next" value={deal.next_action} /> : null}

              <div className="flex items-center justify-between gap-2 pt-0.5">
                {deal.secured ? (
                  <span
                    data-testid="my-deals-secured"
                    className="rounded-md bg-success px-2 py-0.5 text-[11px] font-medium text-success-foreground"
                  >
                    Funds secured
                  </span>
                ) : (
                  <span />
                )}
                {onOpenDeal ? (
                  <button
                    type="button"
                    onClick={() => onOpenDeal(deal.deal_id)}
                    className="text-xs font-medium text-primary hover:underline"
                  >
                    Open deal
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      )}
    </CardShell>
  );
}

// ---------------------------------------------------------------------------
// MetricsCard — get_my_metrics
// ---------------------------------------------------------------------------

export interface MetricsCardProps {
  metrics: MetricsResult;
  className?: string;
}

/**
 * The six strings §8.4 names. Each one is omitted whenever the executor has no metric row
 * (§3.6: "every string field null except `tier` and `data_source`"), so each renders
 * {@link NOT_AVAILABLE} — never a 0 and never a dash, either of which reads as a measurement.
 */
export function MetricsCard({ metrics, className }: MetricsCardProps) {
  const rows: Array<{ label: string; value?: string }> = [
    { label: 'Followers', value: metrics.followers },
    { label: 'Reach (30 days)', value: metrics.reach_30d },
    { label: 'Engagement rate', value: metrics.engagement_rate },
    { label: 'Avg reach per post', value: metrics.avg_reach_per_post },
    { label: 'Quality score', value: metrics.quality_score },
    { label: 'Last verified', value: metrics.verified_at },
  ];

  return (
    <CardShell
      testId="metrics-card"
      className={className}
      title="Your metrics"
      subtitle={
        metrics.connected ? undefined : (
          <p className="text-xs text-muted-foreground">
            No connected account, so these are not measured yet.
          </p>
        )
      }
    >
      <div className="space-y-1.5">
        {rows.map((row) => (
          <Row
            key={row.label}
            label={row.label}
            value={
              row.value ?? (
                <span data-testid="metrics-missing" className="text-muted-foreground">
                  {NOT_AVAILABLE}
                </span>
              )
            }
          />
        ))}
      </div>

      {metrics.tier || metrics.data_source ? (
        <p className="text-xs text-muted-foreground">
          {metrics.tier ? `Tier ${metrics.tier}` : null}
          {metrics.tier && metrics.data_source ? ' · ' : null}
          {metrics.data_source ? `Source: ${metrics.data_source}` : null}
        </p>
      ) : null}
    </CardShell>
  );
}

// ---------------------------------------------------------------------------
// PackageQuoteCard — estimate_my_rate
// ---------------------------------------------------------------------------

export interface PackageQuoteCardProps {
  quote: PackageQuote;
  /**
   * §8.4's "Use in counter". `dealTerms` is in the signature because the counter form takes it
   * (§8.6) — but a `PackageQuote` carries no terms, so this card only ever supplies `amount`.
   * `DraftCard` (B0-49) is the caller that has a real `deal_terms` to pass, off
   * `DraftReplyPayload`.
   */
  onPrefillCounter?: (args: { amount: number; dealTerms?: DealTerms }) => void;
  className?: string;
}

export function PackageQuoteCard({ quote, onPrefillCounter, className }: PackageQuoteCardProps) {
  const benchmark = isBenchmarkQuote(quote);
  const lines: QuoteLine[] = quote.lines ?? [];
  const addOns: AddOnLine[] = quote.add_ons ?? [];

  /**
   * §8.4 — the anchor is HIDDEN when the quote says it is withheld. `anchor`/`anchor_value` are
   * also omitted outright under a negotiation holdout (§2.9), so both conditions are checked;
   * `withheld` is a Java primitive and always present, `anchor` is not.
   */
  const showAnchor = !quote.withheld && !!quote.anchor;

  /**
   * §14.1.b — in benchmark mode the provenance line is the card's SUBTITLE, not a footnote. This
   * is the whole point of the pricing correction: the figures below come from hard-coded tier
   * constants that nobody has checked against a real close, and a creator who does not read that
   * will quote a number we invented. A footnote is how a number stops being read.
   */
  const subtitle = benchmark ? (
    <p data-testid="quote-provenance-subtitle" className="text-sm text-muted-foreground">
      {quote.provenance} — this is our estimate, not what creators like you have actually closed
      at.
    </p>
  ) : undefined;

  return (
    <CardShell
      testId="package-quote-card"
      className={className}
      title="Suggested package"
      subtitle={subtitle}
    >
      {quote.withheld ? (
        <p data-testid="quote-withheld" className="text-sm text-muted-foreground">
          {quote.withheld_reason ?? 'This quote is being withheld for now.'}
        </p>
      ) : null}

      {lines.length > 0 ? (
        <ul className="space-y-1.5">
          {lines.map((line, index) => (
            <li
              key={`${line.type}-${index}`}
              data-testid="quote-line"
              className="flex justify-between gap-3 text-sm"
            >
              <span className="text-muted-foreground">
                {line.qty} × {deliverableLabel(line.type)}
                <span className="ml-1 text-xs">
                  ({money(line.unit_price, quote.currency)} each)
                </span>
              </span>
              <span className="text-right">
                {money(line.line_total, quote.currency)}
                {line.below_floor ? (
                  <span
                    data-testid="quote-below-floor"
                    className="ml-2 rounded bg-destructive px-1.5 py-0.5 text-[11px] font-semibold text-destructive-foreground"
                  >
                    Below your floor
                  </span>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
      ) : null}

      {quote.bundle_discount_value > 0 ? (
        <Row label="Bundle discount" value={`− ${money(quote.bundle_discount, quote.currency)}`} />
      ) : null}

      {addOns.length > 0 ? (
        <div className="space-y-1.5 border-t border-border pt-2">
          {addOns.map((addOn) => (
            <Row
              key={addOn.code}
              label={addOn.label}
              value={money(addOn.amount, quote.currency)}
            />
          ))}
        </div>
      ) : null}

      <div className="border-t border-border pt-2">
        <div className="flex justify-between gap-3 text-sm font-semibold">
          <span>Total</span>
          <span data-testid="quote-total">{money(quote.total, quote.currency)}</span>
        </div>

        {showAnchor ? (
          <div data-testid="quote-anchor" className="mt-1 space-y-0.5">
            <div className="flex justify-between gap-3 text-sm">
              <span className="text-muted-foreground">
                {benchmark ? 'Opening ask (benchmark)' : 'Opening ask'}
              </span>
              <span>{money(quote.anchor, quote.currency)}</span>
            </div>
            {benchmark ? (
              <p className="text-xs text-muted-foreground">
                An opening ask based on a benchmark, not on closed deals.
              </p>
            ) : null}
          </div>
        ) : null}
      </div>

      <div className="space-y-1 text-xs text-muted-foreground">
        <p>
          {quote.payment_schedule} · {quote.revision_rounds} revision
          {quote.revision_rounds === 1 ? '' : 's'}
        </p>
        {/* Non-benchmark provenance keeps its footnote; benchmark provenance is the subtitle
            above and is deliberately NOT repeated here. */}
        {benchmark ? null : (
          <p data-testid="quote-provenance-footnote">
            Based on {quote.provenance}
            {quote.provenance_sample_size > 0 ? ` (${quote.provenance_sample_size})` : ''}.
          </p>
        )}
      </div>

      {onPrefillCounter && !quote.withheld ? (
        <button
          type="button"
          data-testid="quote-use-in-counter"
          onClick={() => onPrefillCounter({ amount: quote.anchor_value ?? quote.total_value })}
          className="w-full rounded-lg border border-border bg-muted px-3 py-2 text-sm font-medium hover:bg-muted/70 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          Use in counter
        </button>
      ) : null}
    </CardShell>
  );
}

// ---------------------------------------------------------------------------
// The switch
// ---------------------------------------------------------------------------

export interface CreatorToolResultRendererProps {
  toolName: string;
  status: 'ok' | 'error';
  /** The raw `tool_result` payload. `unknown` on purpose — narrowed by the §8.2 guards. */
  data?: unknown;
  errorMessage?: string;
  onPrefillCounter?: (args: { amount: number; dealTerms?: DealTerms }) => void;
  onOpenDeal?: (dealId: string) => void;
  className?: string;
}

/**
 * Renders one tool result, or nothing.
 *
 * "Nothing" is the deliberate outcome for three cases: a tool name outside `CREATOR_TOOL_NAMES`
 * (§8.3 — unknown names are ignored with a dev-only warn), a B0 tool whose card has not been
 * built yet (`get_brief`, `draft_reply`), and a payload that fails its type guard. A malformed
 * payload must never take the chat down with it.
 */
export function CreatorToolResultRenderer({
  toolName,
  status,
  data,
  errorMessage,
  onPrefillCounter,
  onOpenDeal,
  className,
}: CreatorToolResultRendererProps) {
  if (!isCreatorToolName(toolName)) {
    if (import.meta.env.DEV) {
      console.warn('[CreatorToolResultRenderer] ignoring unknown tool name:', toolName);
    }
    return null;
  }

  if (status === 'error') {
    return (
      <div
        data-testid="creator-tool-error"
        className={cn(
          'rounded-xl bg-destructive px-3 py-2 text-sm text-destructive-foreground',
          className,
        )}
      >
        {errorMessage ?? 'Meera could not finish that just now.'}
      </div>
    );
  }

  switch (toolName) {
    case 'get_my_deals':
      return isGetMyDealsPayload(data) ? (
        <MyDealsCard
          className={className}
          deals={data.deals}
          activeCount={data.active_count}
          completedCount={data.completed_count}
          onOpenDeal={onOpenDeal}
        />
      ) : null;

    case 'get_my_metrics':
      return isGetMyMetricsPayload(data) ? (
        <MetricsCard className={className} metrics={data.metrics} />
      ) : null;

    case 'estimate_my_rate':
      return isEstimateMyRatePayload(data) ? (
        <PackageQuoteCard
          className={className}
          quote={data.quote}
          onPrefillCounter={onPrefillCounter}
        />
      ) : null;

    case 'check_deal_risks':
      return isCheckDealRisksPayload(data) ? (
        <DealRiskCard
          className={className}
          flags={data.flags}
          heading={<p className="text-sm font-semibold">What to watch on this deal</p>}
        />
      ) : null;

    // B0-44 (`BriefCard`) and B0-49 (`DraftCard`) — deliberately unrendered until those land.
    case 'get_brief':
    case 'draft_reply':
      return null;

    default:
      return null;
  }
}

export default CreatorToolResultRenderer;
