import * as React from 'react';
import { AlertTriangle, ArrowUpRight, Gavel, Loader2 } from 'lucide-react';
import { Link } from 'react-router-dom';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { api, ApiError, type BrandDisputeRow, type DisputeLifecycleStatus } from '@/lib/api';
import { formatCurrency, formatDate } from '@/lib/helpers';
import { statusBadgeClass } from '@/lib/stage-colors';

const LIFECYCLE_LABEL: Record<DisputeLifecycleStatus, string> = {
  OPEN: 'Open',
  UNDER_REVIEW: 'Under review',
  RESOLVED_BRAND: 'Resolved — in your favor',
  RESOLVED_CREATOR: "Resolved — creator's favor",
  RESOLVED_SPLIT: 'Resolved — split',
};

/**
 * Brand disputes — F3 (BRAND_EXECUTION_PLAN.md).
 * Read-only tracking view. Opening a dispute stays in the deal room
 * (POST /deals/{dealId}/disputes); this page only lists what's already open
 * or resolved. Live mode calls the real GET /brand/disputes/list
 * (BrandDisputeController, P2-14 — see src/lib/api.ts), which returns full
 * display fields (status, opened date, reason), so the "partial data" banner
 * below only fires if the backend ever omits `disputeStatus` (defensive, not
 * expected in normal operation).
 *
 * No <BrandLayout> wrap here — the route (App.tsx) already applies it via
 * BrandLayoutWrapper. (brand-reviews.tsx self-wraps too, which double-nests
 * the sidebar/header at /brand/reviews — verified in the browser preview.
 * Not fixed here since it's a pre-existing bug outside F3's scope.)
 */
export default function BrandDisputesPage() {
  const [disputes, setDisputes] = React.useState<BrandDisputeRow[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);

  const refresh = React.useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const rows = await api.brandDisputes.list();
      setDisputes(rows);
    } catch (err) {
      setDisputes([]);
      setError(err instanceof ApiError ? err.message : 'Could not load disputes.');
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void refresh();
  }, [refresh]);

  // Live-mode rows never carry `disputeStatus` (see api.ts gap note) — detect
  // that case to show the "partial data" banner rather than assuming demo mode.
  const hasPartialData = disputes.some((d) => d.disputeStatus === undefined);

  return (
    <div className="container mx-auto max-w-3xl px-4 py-6">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Disputes</h1>
          <p className="text-muted-foreground">
            Track disputes opened on your collaborations. To open a new dispute, go to the
            relevant deal room.
          </p>
        </div>

        {hasPartialData && (
          <Alert className="mb-5 border-amber-300 bg-amber-50">
            <AlertTriangle className="h-4 w-4 text-amber-700" />
            <AlertTitle className="text-amber-900">Showing partial data</AlertTitle>
            <AlertDescription className="text-amber-800">
              There is no dispute-list endpoint for brands yet, so this only shows which deals are
              currently marked disputed — not the reason, review stage, or resolution. Full detail
              needs{' '}
              <code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-xs">
                GET /brand/disputes
              </code>{' '}
              on the backend.
            </AlertDescription>
          </Alert>
        )}

        {error && (
          <Alert variant="destructive" className="mb-5">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Could not load disputes</AlertTitle>
            <AlertDescription>
              {error}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={() => void refresh()}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label="Loading disputes" />
          </div>
        ) : disputes.length === 0 && !error ? (
          <EmptyState />
        ) : (
          <div className="space-y-3">
            {disputes.map((dispute) => (
              <DisputeCard key={dispute.collaborationId} dispute={dispute} />
            ))}
          </div>
        )}
      </div>
  );
}

function DisputeCard({ dispute }: { dispute: BrandDisputeRow }) {
  const lifecycle = dispute.disputeStatus;
  const badgeLabel = lifecycle ? LIFECYCLE_LABEL[lifecycle] : 'Disputed';

  return (
    <Card className="border-border/80">
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="font-medium truncate">{dispute.campaignName}</p>
            <p className="text-sm text-muted-foreground truncate">
              with {dispute.counterpartyName}
            </p>
          </div>
          <Badge className={statusBadgeClass(lifecycle ?? 'DISPUTED')}>{badgeLabel}</Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
          <span>Deal value: {formatCurrency(dispute.dealValue, dispute.currency)}</span>
          {dispute.openedAt && <span>Opened {formatDate(dispute.openedAt)}</span>}
        </div>

        {dispute.reason && (
          <p className="text-sm text-foreground/90 leading-relaxed">{dispute.reason}</p>
        )}

        {dispute.resolutionNotes && (
          <div className="rounded-lg border border-border bg-muted/40 p-3">
            <p className="text-xs font-medium text-muted-foreground">
              Resolution{dispute.resolvedAt ? ` — ${formatDate(dispute.resolvedAt)}` : ''}
            </p>
            <p className="mt-1 text-sm">{dispute.resolutionNotes}</p>
          </div>
        )}

        <div>
          <Button asChild size="sm" variant="outline">
            <Link to={`/brand/chat?deal=${dispute.collaborationId}`}>
              View deal room
              <ArrowUpRight className="ml-1.5 h-3.5 w-3.5" />
            </Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-muted">
        <Gavel className="h-7 w-7 text-muted-foreground" />
      </div>
      <p className="font-medium">No disputes</p>
      <p className="mt-1 max-w-sm text-sm text-muted-foreground">
        Disputes opened from a deal room will show up here so you can track their status and
        resolution.
      </p>
    </div>
  );
}
