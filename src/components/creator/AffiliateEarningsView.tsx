import { AlertTriangle, IndianRupee, Loader2 } from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useAffiliateEarnings } from '@/hooks/creator/useAffiliateEarnings';
import type { AffiliateEarningRow, AffiliateEarningsSummary } from '@/lib/api';

/**
 * Affiliate earnings — live view backed by GET /creator/affiliate-earnings
 * (CreatorAffiliateEarningController / AffiliateEarningsService, Wave D).
 *
 * One commission row per attributed sale (`AffiliateEarning`: PENDING accrues
 * from conversion webhooks, SETTLED once the monthly AffiliateSettlementJob
 * pays the period out — terminal, no path back). The four headline cards come
 * from the endpoint's precomputed summary, not client-side math, so they stay
 * consistent with the backend's own settlement accounting.
 *
 * (Replaced the Wave A "coming soon" placeholder on 2026-07-17 — the backend
 * this file's old comment said "has not started" had in fact shipped:
 * entity + V28 migration + settlement/reconciliation jobs + endpoint, with
 * `useAffiliateEarnings` already wired and waiting.)
 */
export function AffiliateEarningsView() {
  const { data, summary, loading, loadingMore, hasMore, error, notImplemented, refresh, loadMore } =
    useAffiliateEarnings();

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Affiliate Earnings</h2>
        <p className="text-muted-foreground">
          Commission you've earned from affiliate-model campaigns — one row per attributed sale.
        </p>
      </div>

      {notImplemented && (
        <Alert>
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Earnings temporarily unavailable</AlertTitle>
          <AlertDescription>
            The earnings service didn't respond with data — try again shortly.
          </AlertDescription>
        </Alert>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>Couldn't load your earnings</AlertTitle>
          <AlertDescription>
            {error}
            <div className="mt-2">
              <Button size="sm" variant="outline" onClick={() => refresh()}>
                Try again
              </Button>
            </div>
          </AlertDescription>
        </Alert>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : (
        <>
          {summary && <SummaryCards summary={summary} />}
          {data.length === 0 && !error && !notImplemented ? (
            <EmptyState />
          ) : (
            <div className="space-y-4">
              {data.map((earning) => (
                <EarningCard key={earning.id} earning={earning} />
              ))}
              {/* CR-83 — this response now pages instead of returning every row ever
                  recorded in one shot; this is the load-more affordance for the rest. */}
              {hasMore && (
                <div className="flex justify-center pt-2">
                  <Button variant="outline" onClick={() => loadMore()} disabled={loadingMore}>
                    {loadingMore ? (
                      <>
                        <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                        Loading...
                      </>
                    ) : (
                      'Load more'
                    )}
                  </Button>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function SummaryCards({ summary }: { summary: AffiliateEarningsSummary }) {
  const stats = [
    { label: 'Sales this month', value: String(summary.thisMonthSales) },
    { label: 'Revenue this month', value: formatMoney(summary.thisMonthRevenue, summary.currency) },
    { label: 'Commission this month', value: formatMoney(summary.thisMonthCommission, summary.currency) },
    { label: 'Unsettled commission', value: formatMoney(summary.unsettledCommission, summary.currency) },
  ];

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {stats.map((stat) => (
        <Card key={stat.label}>
          <CardContent className="p-4">
            <p className="text-xs text-muted-foreground">{stat.label}</p>
            <p className="mt-1 text-lg font-semibold tabular-nums">{stat.value}</p>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}

function EarningCard({ earning }: { earning: AffiliateEarningRow }) {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="truncate font-medium">{earning.campaignName}</p>
            <p className="text-sm text-muted-foreground">{earning.brandName}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              Order <span className="font-mono">{earning.orderId}</span> ·{' '}
              {formatMoney(earning.orderTotal, earning.currency)} ·{' '}
              {formatDate(earning.createdAt)}
            </p>
          </div>
          <div className="shrink-0 text-right">
            <p className="font-semibold tabular-nums">
              {formatMoney(earning.commissionAmount, earning.currency)}
            </p>
            <StatusBadge status={earning.status} settledAt={earning.settledAt} />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * AffiliateEarning.Status is PENDING -> SETTLED (terminal). Unknown values
 * (future statuses) render as a neutral outline badge rather than breaking.
 */
function StatusBadge({ status, settledAt }: { status: string; settledAt: string | null }) {
  if (status === 'SETTLED') {
    return (
      <Badge className="mt-1 bg-emerald-100 text-emerald-800 hover:bg-emerald-100">
        Settled{settledAt ? ` ${formatDate(settledAt)}` : ''}
      </Badge>
    );
  }
  if (status === 'PENDING') {
    return (
      <Badge className="mt-1 bg-amber-100 text-amber-800 hover:bg-amber-100">
        Pending settlement
      </Badge>
    );
  }
  return (
    <Badge variant="outline" className="mt-1">
      {status}
    </Badge>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <IndianRupee className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No affiliate earnings yet</p>
      <p className="mt-1 max-w-xs text-sm text-muted-foreground">
        When a sale is attributed to one of your affiliate campaigns, your commission for it will
        show up here — pending until the monthly settlement pays it out.
      </p>
    </div>
  );
}

function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: currency || 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export default AffiliateEarningsView;
