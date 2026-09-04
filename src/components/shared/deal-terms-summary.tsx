import type { DealTerms, UsageChannel } from '@/lib/types';

/**
 * T-MEERA-CREATOR-PHASE-A gate-fix round 2 (Priya Q1) — read-side render of the structured
 * deal terms a brand captures on an offer (`api.deals.create`/`counter`'s `dealTerms`
 * payload, persisted onto the Collaboration and served back as `Deal.dealTerms`).
 *
 * Priya's finding: the brand's form wrote these fields and DealService persisted and served
 * them, but nothing on either side of the deal room ever read them back — "the brand fills
 * the form and no one ever sees the result." This is the shared render, used by:
 *   - the creator's offer view (`creator-chat.tsx` proposal card, `creator-deals.tsx` detail)
 *   - the brand's deal detail (`brand-chat.tsx` proposal card)
 *
 * Renders nothing when `terms` is undefined (the backend field is `@JsonInclude(NON_NULL)` —
 * omitted, not `null`, whenever no structured terms were ever set on this Collaboration, e.g.
 * every pre-A2 deal). Callers gate on presence themselves so the whole "Deal Terms" section —
 * heading included — simply doesn't render for those deals, rather than showing an empty or
 * zeroed block.
 */

const USAGE_CHANNEL_LABELS: Record<UsageChannel, string> = {
  ORGANIC: 'Organic social',
  PAID_ADS: 'Paid ads',
  WHITELISTING: 'Whitelisting',
  WEBSITE: 'Website',
  OFFLINE: 'Offline / print',
};

function formatUsageWindow(terms: DealTerms): string {
  if (terms.usagePerpetual) return 'Perpetual';
  if (terms.usageMonths != null) return `${terms.usageMonths} month${terms.usageMonths === 1 ? '' : 's'}`;
  return 'Not specified';
}

function formatUsageChannels(terms: DealTerms): string {
  if (terms.usageChannels.length === 0) return 'Not specified';
  return terms.usageChannels.map((c) => USAGE_CHANNEL_LABELS[c] ?? c).join(', ');
}

function formatExclusivity(terms: DealTerms): string {
  const days = terms.exclusivityDays != null ? ` (${terms.exclusivityDays} day${terms.exclusivityDays === 1 ? '' : 's'})` : '';
  switch (terms.exclusivityScope) {
    case 'NONE':
      return 'None';
    case 'CATEGORY':
      return `Category exclusivity${days}`;
    case 'NAMED_BRANDS':
      return terms.exclusivityBrands.length > 0
        ? `Excluded: ${terms.exclusivityBrands.join(', ')}${days}`
        : `Named brands${days}`;
    default:
      return 'Not specified';
  }
}

export interface DealTermsSummaryProps {
  terms: DealTerms;
  /** Compact = inline label/value rows matching the proposal card's existing "Amount"/"Deliverables" rows. Default. */
  variant?: 'compact' | 'default';
  className?: string;
}

export function DealTermsSummary({ terms, variant = 'compact', className }: DealTermsSummaryProps) {
  const rows: Array<{ label: string; value: string }> = [
    { label: 'Usage window', value: formatUsageWindow(terms) },
    { label: 'Usage channels', value: formatUsageChannels(terms) },
    { label: 'Exclusivity', value: formatExclusivity(terms) },
    { label: 'Max revisions', value: String(terms.maxRevisions) },
  ];

  if (variant === 'compact') {
    return (
      <div className={className}>
        {rows.map((row) => (
          <div key={row.label} className="flex justify-between gap-3 text-sm">
            <span className="text-muted-foreground">{row.label}</span>
            <span className="text-right">{row.value}</span>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className={className}>
      <p className="text-sm font-medium mb-2">Deal terms</p>
      <div className="space-y-1.5 rounded-lg border bg-muted/30 p-3">
        {rows.map((row) => (
          <div key={row.label} className="flex justify-between gap-3 text-sm">
            <span className="text-muted-foreground">{row.label}</span>
            <span className="text-right">{row.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
