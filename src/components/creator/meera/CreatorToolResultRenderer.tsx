import * as React from 'react';
import { cn, formatINR } from '@/lib/utils';
import { DealRiskCard } from '@/components/shared/deal-risk-card';
import { useRiskFlagDismissals } from '@/hooks/useRiskFlagDismissals';
import type {
  AddOnLine,
  BriefAnalysisResponse,
  BriefExtraction,
  PackageQuote,
  QuoteDeliverableType,
  QuoteLine,
  RiskFlag,
} from '@/lib/api';
import type { DealTerms, UsageChannel } from '@/lib/types';
import {
  isCheckDealRisksPayload,
  isCreatorToolName,
  isEstimateMyRatePayload,
  isGetBriefPayload,
  isGetMyDealsPayload,
  isGetMyMetricsPayload,
  isNewBriefStub,
  type AccountLast28Days,
  type CheckDealRisksPayload,
  type DealSummary,
  type GetBriefPayload,
  type MetricsResult,
} from '@/lib/meera-api';

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md §8.4, B0-37) — the creator-side card library for Meera's tool
 * results, plus the switch that picks a card for a tool name.
 *
 * ## Which cards exist here
 * Phase B0 ships six creator tools (§14.5.a). Five of them can be rendered with what B0 has
 * actually built, and those five cards are here: `MyDealsCard` (`get_my_deals`), `MetricsCard`
 * (`get_my_metrics`), `PackageQuoteCard` (`estimate_my_rate`), `DealRiskCard`
 * (`check_deal_risks`, imported from `components/shared/` because the deal pages use it too,
 * §8.6), and `BriefCard` (`get_brief`, wired up as of U-4 via `GetBriefToolCard` below).
 * `BriefCard` itself exists as of U-2 and is also rendered directly by the paste surface
 * (`components/creator/copilot/PasteBriefCard.tsx`). `DraftCard` (`draft_reply`) still lands with
 * the approval flow in Wave D — until then that one tool name renders nothing rather than a
 * placeholder that claims a feature exists.
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
  /** 2026-09-24 addition — the account's real last-28-days Meta insights totals, alongside (not
   *  instead of) `metrics`. Absent entirely on an older cached payload; `.available` false when
   *  the backend has fetched nothing yet. See `AccountLast28Days`'s own doc comment in
   *  `meera-api.ts` for why its fields are `string | null`, not just optional. */
  accountLast28Days?: AccountLast28Days;
  className?: string;
}

/** One `AccountLast28Days` figure with its label — used to build the "Last 28 days" group below,
 *  skipping any figure that is `null`/`undefined` (never inventing a "0" for a figure Meta did not
 *  return; an actual reported `"0"` is a non-empty string and still renders). */
const LAST_28_DAYS_FIELDS: Array<{ label: string; key: keyof AccountLast28Days }> = [
  { label: 'Accounts reached', key: 'accounts_reached' },
  { label: 'Views', key: 'views' },
  { label: 'Interactions', key: 'interactions' },
  { label: 'Accounts engaged', key: 'accounts_engaged' },
  { label: 'Profile link taps', key: 'profile_link_taps' },
];

/**
 * The five strings §8.4 names, MINUS "Reach (30 days)" — that field is `undefined` on every
 * account, live and otherwise, because no 30-day reach total is stored anywhere (see
 * `MetricsResult.reach_30d`'s own comment); showing a permanent "Not available yet" for a number
 * the platform will never have was worse than not showing the row at all. Each of the five
 * remaining strings is still omitted whenever the executor has no metric row (§3.6: "every string
 * field null except `tier` and `data_source`"), so each still renders {@link NOT_AVAILABLE} — never
 * a 0 and never a dash, either of which reads as a measurement.
 *
 * Below that: a real, backend-computed "Last 28 days" group from `accountLast28Days`, shown only
 * when `.available` is true. Nothing renders here at all when it is false or the prop is absent —
 * not even a "not available" line — because unlike the metrics above, this group did not exist as
 * a promise the card made before; there is nothing to apologise for not having yet.
 */
export function MetricsCard({ metrics, accountLast28Days, className }: MetricsCardProps) {
  const rows: Array<{ label: string; value?: string }> = [
    { label: 'Followers', value: metrics.followers },
    { label: 'Engagement rate', value: metrics.engagement_rate },
    { label: 'Avg reach per post', value: metrics.avg_reach_per_post },
    { label: 'Quality score', value: metrics.quality_score },
    { label: 'Last verified', value: metrics.verified_at },
  ];

  const last28 = accountLast28Days?.available
    ? LAST_28_DAYS_FIELDS.filter((f) => !!accountLast28Days[f.key])
    : [];

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

      {last28.length > 0 ? (
        <div data-testid="metrics-last-28-days" className="space-y-1.5 border-t border-border pt-2">
          <p className="text-xs font-medium text-muted-foreground">
            Last 28 days{accountLast28Days?.period ? ` (${accountLast28Days.period})` : ''}
          </p>
          {last28.map((f) => (
            <Row key={f.key} label={f.label} value={accountLast28Days![f.key] as string} />
          ))}
        </div>
      ) : null}

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
// BriefCard — the paste surface (U-2, §8.4, §8.5)
// ---------------------------------------------------------------------------

/**
 * U-2 (AMEND-0904, §14.4.a) — the sentence that says a summary was NOT read by the model.
 *
 * `BriefDtos.BriefAnalysisResponse`'s javadoc is explicit that `cap` and `ai_unavailable` "must
 * reach the creator as different sentences", and that a FALLBACK with no reason is "how a
 * degraded reading gets rendered as a real one". So all three shapes get a label, and only an
 * AI extraction with no reason gets none. `BriefFallbackExtractor` leaves every field it cannot
 * find with confidence empty, hence the second sentence.
 */
export function degradedLabelFor(
  extractionSource: BriefAnalysisResponse['extraction_source'],
  degradedReason: BriefAnalysisResponse['degraded_reason'],
): string | undefined {
  if (degradedReason === 'cap') {
    return "Meera's monthly limit is reached, so this summary is rule-based. Check it against the brief.";
  }
  if (degradedReason === 'ai_unavailable') {
    return "Meera couldn't be reached just now, so this summary is rule-based. Check it against the brief.";
  }
  if (extractionSource === 'FALLBACK') {
    return 'This summary is rule-based, not read by Meera. Check it against the brief.';
  }
  return undefined;
}

const USAGE_CHANNEL_LABELS: Record<UsageChannel, string> = {
  ORGANIC: 'organic',
  PAID_ADS: 'paid ads',
  WHITELISTING: 'whitelisting',
  WEBSITE: 'website',
  OFFLINE: 'offline',
};

/** Absent on the wire means the extractor did not find it — not that the brief ruled it out. */
const NOT_FOUND = 'Not found';

interface ExtractionChip {
  label: string;
  value?: string;
}

/**
 * §8.4's five chips. Every optional field is checked for presence (`!== undefined`, or a length
 * check on an array) — never `=== null`, which a NON_NULL-omitted key never satisfies.
 */
function extractionChips(extraction: BriefExtraction): ExtractionChip[] {
  const deliverables = extraction.deliverables ?? [];
  const channels = extraction.usage_channels ?? [];
  const exclusivityBrands = extraction.exclusivity_brands ?? [];

  let budget: string | undefined;
  if (extraction.budget_inr !== undefined) {
    budget = formatINR(extraction.budget_inr);
  } else if (extraction.barter_only) {
    budget =
      extraction.barter_mrp_inr !== undefined
        ? `Barter only (product worth ${formatINR(extraction.barter_mrp_inr)})`
        : 'Barter only';
  }

  let usage: string | undefined;
  if (extraction.usage_perpetual) {
    usage = 'Forever';
  } else if (extraction.usage_months !== undefined) {
    usage = `${extraction.usage_months} month${extraction.usage_months === 1 ? '' : 's'}`;
  }
  if (usage && channels.length > 0) {
    usage += ` · ${channels.map((c) => USAGE_CHANNEL_LABELS[c] ?? c).join(', ')}`;
  }

  let exclusivity: string | undefined;
  if (extraction.exclusivity_days !== undefined) {
    exclusivity = `${extraction.exclusivity_days} day${extraction.exclusivity_days === 1 ? '' : 's'}`;
    if (exclusivityBrands.length > 0) {
      exclusivity += ` · ${exclusivityBrands.join(', ')}`;
    } else if (extraction.exclusivity_scope === 'CATEGORY') {
      exclusivity += ' · whole category';
    }
  }

  return [
    {
      label: 'Deliverables',
      value:
        deliverables.length > 0
          ? deliverables.map((d) => `${d.qty} × ${deliverableLabel(d.type)}`).join(', ')
          : undefined,
    },
    { label: 'Budget', value: budget },
    { label: 'Deadline', value: extraction.deadline },
    { label: 'Usage', value: usage },
    { label: 'Exclusivity', value: exclusivity },
  ];
}

export interface BriefCardProps {
  summaryLines?: string[];
  extraction?: BriefExtraction;
  flags?: RiskFlag[];
  quote?: PackageQuote;
  extractionSource?: BriefAnalysisResponse['extraction_source'];
  degradedReason?: BriefAnalysisResponse['degraded_reason'];
  /**
   * U-3: dismissal scope for the risk flags, `BRIEF:{brief_id}`. Without one the flags render
   * with no dismiss control, rather than sharing one bucket across unrelated briefs.
   */
  riskScope?: string;
  onPrefillCounter?: (args: { amount: number; dealTerms?: DealTerms }) => void;
  className?: string;
}

/**
 * §8.4 — summary lines, extraction chips, then the shared `DealRiskCard` and `PackageQuoteCard`.
 * Every part is optional because `GET /creator/briefs/:id` can omit any of them (see
 * `BriefAnalysisResponse`); a missing part renders its own absence, never a crash.
 */
export function BriefCard({
  summaryLines,
  extraction,
  flags,
  quote,
  extractionSource,
  degradedReason,
  riskScope,
  onPrefillCounter,
  className,
}: BriefCardProps) {
  const lines = summaryLines ?? [];
  const degradedLabel = degradedLabelFor(extractionSource, degradedReason);
  const risks = useRiskFlagDismissals(riskScope, flags);

  return (
    <div data-testid="brief-card" className={cn('space-y-3', className)}>
      <CardShell testId="brief-summary-card" title="What the brief says">
        {degradedLabel ? (
          <p
            data-testid="brief-degraded-label"
            role="status"
            className="rounded-lg border border-stage-negotiating-border bg-stage-negotiating px-3 py-2 text-sm text-stage-negotiating-fg"
          >
            {degradedLabel}
          </p>
        ) : null}

        {lines.length > 0 ? (
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {lines.map((line, index) => (
              <li key={`${index}-${line}`} data-testid="brief-summary-line" className="break-words">
                {line}
              </li>
            ))}
          </ul>
        ) : null}

        {extraction ? (
          <ul className="flex flex-wrap gap-2" aria-label="Terms found in the brief">
            {extractionChips(extraction).map((chip) => (
              <li
                key={chip.label}
                data-testid="brief-chip"
                className="rounded-md border border-border bg-muted px-2 py-1 text-xs"
              >
                <span className="text-muted-foreground">{chip.label}: </span>
                {chip.value ? (
                  <span className="font-medium break-words">{chip.value}</span>
                ) : (
                  <span className="text-muted-foreground">{NOT_FOUND}</span>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No terms could be read from this brief.</p>
        )}
      </CardShell>

      <DealRiskCard
        flags={risks.visibleFlags}
        onDismiss={risks.dismiss}
        hiddenCount={risks.hiddenCount}
        onRestoreHidden={risks.restore}
        heading={<p className="text-sm font-semibold">What to watch</p>}
      />

      {quote ? (
        <PackageQuoteCard quote={quote} onPrefillCounter={onPrefillCounter} />
      ) : (
        <p data-testid="brief-no-quote" className="text-sm text-muted-foreground">
          No price suggestion for this brief.
        </p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// DealRisksToolCard — check_deal_risks, with session dismissal (U-3)
// ---------------------------------------------------------------------------

function DealRisksToolCard({
  payload,
  className,
}: {
  payload: CheckDealRisksPayload;
  className?: string;
}) {
  // The guard only proves `flags` is an array, so the scope parts are checked here. A payload
  // without a usable target gets no dismiss control rather than a shared, wrong bucket.
  const scope =
    typeof payload.target === 'string' && typeof payload.target_id === 'string' && payload.target_id
      ? `${payload.target}:${payload.target_id}`
      : undefined;
  const risks = useRiskFlagDismissals(scope, payload.flags);

  return (
    <DealRiskCard
      className={className}
      flags={risks.visibleFlags}
      onDismiss={risks.dismiss}
      hiddenCount={risks.hiddenCount}
      onRestoreHidden={risks.restore}
      heading={<p className="text-sm font-semibold">What to watch on this deal</p>}
    />
  );
}

// ---------------------------------------------------------------------------
// GetBriefToolCard — get_brief (U-4)
// ---------------------------------------------------------------------------

/**
 * `get_brief` reuses `BriefCard` wholesale — the same summary/chips, the shared `DealRiskCard`
 * dismissal wiring and `PackageQuoteCard` that `check_deal_risks`/`estimate_my_rate` already use —
 * rather than a second copy of any of it. Two things are deliberately NOT done here:
 *   1. No `degraded_reason` is read or passed through. `GetBriefPayload` (`meera-api.ts`) has no
 *      such field; `BriefCard`'s `degradedLabelFor` still labels a `FALLBACK` extraction on its
 *      own ("This summary is rule-based, not read by Meera"), which is all this tool result can
 *      honestly say. Whether the backend DTO ever grows a `degraded_reason` for this tool is
 *      Vikram's call, not this renderer's.
 *   2. `GetBriefPayload` carries no `summary_lines` — only `PASTED`/`PLATFORM` briefs read through
 *      `BriefAnalysisResponse` have those. `BriefCard` already treats an absent `summaryLines` as
 *      "nothing to list" rather than an error.
 */
/** The plain "not analyzed yet" state — factored out so both a real NEW `GetBriefPayload` and the
 *  defence-in-depth stub fallback (a payload that fails the full guard but still looks like a NEW
 *  brief) render the exact same honest message, never a clean-looking card. */
function StillReadingBriefCard({ className }: { className?: string }) {
  return (
    <CardShell testId="get-brief-card" className={className} title="This brief">
      <p data-testid="get-brief-still-reading" className="text-sm text-muted-foreground">
        Still reading this brief. Check back in a moment.
      </p>
    </CardShell>
  );
}

function GetBriefToolCard({
  payload,
  className,
  onPrefillCounter,
}: {
  payload: GetBriefPayload;
  className?: string;
  onPrefillCounter?: (args: { amount: number; dealTerms?: DealTerms }) => void;
}) {
  // A brand-new brief has not been analyzed yet: `flags`/`quote` on the wire are placeholders
  // (an empty flags array, a withheld/zeroed quote), never a real "no risks found" verdict. A
  // BriefCard built on those placeholders would read as a clean deal — CR-plausible-looking but
  // false. Say plainly that Meera has not read it yet instead.
  if (payload.status === 'NEW') {
    return <StillReadingBriefCard className={className} />;
  }

  return (
    <BriefCard
      className={className}
      extraction={payload.extraction}
      flags={payload.flags}
      quote={payload.quote}
      extractionSource={payload.extraction_source}
      riskScope={`BRIEF:${payload.brief_id}`}
      onPrefillCounter={onPrefillCounter}
    />
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
 * (§8.3 — unknown names are ignored with a dev-only warn), the one B0 tool whose card has not been
 * built yet (`draft_reply`, landing with the approval flow in Wave D), and a payload that fails
 * its type guard. A malformed payload must never take the chat down with it.
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
        <MetricsCard
          className={className}
          metrics={data.metrics}
          accountLast28Days={data.account_last_28_days}
        />
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
        <DealRisksToolCard className={className} payload={data} />
      ) : null;

    case 'get_brief':
      if (isGetBriefPayload(data)) {
        return (
          <GetBriefToolCard
            className={className}
            payload={data}
            onPrefillCounter={onPrefillCounter}
          />
        );
      }
      // Defence in depth (KAVYA-FE-RECHECK-0917.md) — see `isNewBriefStub`'s own doc comment.
      // Should be unreachable: the backend refuses a NEW/incomplete brief with 409 before this.
      return isNewBriefStub(data) ? <StillReadingBriefCard className={className} /> : null;

    // B0-49 (`DraftCard`) — deliberately unrendered until it lands with Wave D's approval flow.
    case 'draft_reply':
      return null;

    default:
      return null;
  }
}

export default CreatorToolResultRenderer;
