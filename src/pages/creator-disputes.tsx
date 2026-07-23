import * as React from 'react';
import { AlertTriangle, ArrowUpRight, Gavel, Loader2 } from 'lucide-react';
import { Link } from 'react-router-dom';

import { CreatorLayout } from '@/components/creator/creator-layout';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import {
  api,
  ApiError,
  type CreatorDisputeRow,
  type Deal,
  type DisputeLifecycleStatus,
} from '@/lib/api';
import { formatCurrency, formatDate } from '@/lib/helpers';
import { statusBadgeClass } from '@/lib/stage-colors';

const LIFECYCLE_LABEL: Record<DisputeLifecycleStatus, string> = {
  OPEN: 'Open',
  UNDER_REVIEW: 'Under review',
  RESOLVED_BRAND: "Resolved — brand's favor",
  RESOLVED_CREATOR: 'Resolved — in your favor',
  RESOLVED_SPLIT: 'Resolved — split',
};

/**
 * Creator disputes — Task #38 (CEO §1.3).
 * List open/resolved disputes + open a new dispute on an eligible funded deal.
 * No refund/release/money-movement UI (v1 status-only).
 *
 * Live list calls the real GET /creator/disputes (CreatorDisputeController,
 * 2026-07-23) — full detail (reason, review stage, resolution) every time,
 * no partial-data fallback needed.
 */
export default function CreatorDisputesPage() {
  const [disputes, setDisputes] = React.useState<CreatorDisputeRow[]>([]);
  const [eligibleDeals, setEligibleDeals] = React.useState<Deal[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState<string | null>(null);

  const [selectedDealId, setSelectedDealId] = React.useState('');
  const [reason, setReason] = React.useState('');
  const [submitting, setSubmitting] = React.useState(false);
  const [submitError, setSubmitError] = React.useState<string | null>(null);
  const [submitSuccess, setSubmitSuccess] = React.useState<string | null>(null);
  const [eligibleLoading, setEligibleLoading] = React.useState(true);
  const [eligibleError, setEligibleError] = React.useState<string | null>(null);

  const refreshList = React.useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const rows = await api.creatorDisputes.list();
      setDisputes(rows);
    } catch (err) {
      setDisputes([]);
      setError(err instanceof ApiError ? err.message : 'Could not load disputes.');
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshEligible = React.useCallback(async () => {
    setEligibleLoading(true);
    setEligibleError(null);
    try {
      const rows = await api.creatorDisputes.listEligibleDeals();
      setEligibleDeals(rows);
    } catch (err) {
      setEligibleDeals([]);
      setEligibleError(
        err instanceof ApiError ? err.message : 'Could not load eligible deals.',
      );
    } finally {
      setEligibleLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void refreshList();
    void refreshEligible();
  }, [refreshList, refreshEligible]);

  const canSubmit =
    Boolean(selectedDealId) && reason.trim().length >= 10 && !submitting;

  const handleOpenDispute = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;

    setSubmitting(true);
    setSubmitError(null);
    setSubmitSuccess(null);
    try {
      await api.creatorDisputes.open(selectedDealId, reason.trim());
      setSubmitSuccess('Dispute opened. An admin will review the case.');
      setSelectedDealId('');
      setReason('');
      await Promise.all([refreshList(), refreshEligible()]);
    } catch (err) {
      setSubmitError(
        err instanceof ApiError ? err.message : 'Could not open dispute. Try again.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <CreatorLayout>
      <div className="container mx-auto max-w-3xl px-4 py-6">
        <div className="mb-6">
          <h1 className="text-2xl font-bold">Disputes</h1>
          <p className="text-muted-foreground">
            Open a dispute on a funded collaboration or track cases already under review.
            Resolution is admin-mediated — no automatic refund or release in v1.
          </p>
        </div>

        <OpenDisputeForm
          eligibleDeals={eligibleDeals}
          eligibleLoading={eligibleLoading}
          eligibleError={eligibleError}
          selectedDealId={selectedDealId}
          reason={reason}
          submitting={submitting}
          submitError={submitError}
          submitSuccess={submitSuccess}
          canSubmit={canSubmit}
          onDealChange={setSelectedDealId}
          onReasonChange={setReason}
          onSubmit={handleOpenDispute}
          onRetryEligible={() => void refreshEligible()}
        />

        <h2 className="mb-3 mt-8 text-lg font-semibold">Your disputes</h2>

        {error && (
          <Alert variant="destructive" className="mb-5">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Could not load disputes</AlertTitle>
            <AlertDescription>
              {error}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={() => void refreshList()}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2
              className="h-6 w-6 animate-spin text-muted-foreground"
              aria-label="Loading disputes"
            />
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
    </CreatorLayout>
  );
}

function OpenDisputeForm({
  eligibleDeals,
  eligibleLoading,
  eligibleError,
  selectedDealId,
  reason,
  submitting,
  submitError,
  submitSuccess,
  canSubmit,
  onDealChange,
  onReasonChange,
  onSubmit,
  onRetryEligible,
}: {
  eligibleDeals: Deal[];
  eligibleLoading: boolean;
  eligibleError: string | null;
  selectedDealId: string;
  reason: string;
  submitting: boolean;
  submitError: string | null;
  submitSuccess: string | null;
  canSubmit: boolean;
  onDealChange: (id: string) => void;
  onReasonChange: (value: string) => void;
  onSubmit: (e: React.FormEvent) => void;
  onRetryEligible: () => void;
}) {
  return (
    <Card className="mb-2 border-border/80">
      <CardHeader className="pb-3">
        <p className="font-medium">Open a dispute</p>
        <p className="text-sm text-muted-foreground">
          Only deals with funded escrow (not already disputed, completed, or cancelled) can be
          disputed. Opening freezes unreleased escrow for admin review.
        </p>
      </CardHeader>
      <CardContent>
        {eligibleError && (
          <Alert variant="destructive" className="mb-4">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Could not load eligible deals</AlertTitle>
            <AlertDescription>
              {eligibleError}
              <div className="mt-2">
                <Button size="sm" variant="outline" onClick={onRetryEligible}>
                  Try again
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}

        {eligibleLoading ? (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            Loading eligible deals…
          </div>
        ) : eligibleDeals.length === 0 && !eligibleError ? (
          <p className="py-2 text-sm text-muted-foreground">
            No eligible deals right now. You need a funded collaboration that is not already
            disputed, completed, or cancelled.
          </p>
        ) : (
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="dispute-deal">Collaboration</Label>
              <Select value={selectedDealId || undefined} onValueChange={onDealChange}>
                <SelectTrigger id="dispute-deal" className="w-full">
                  <SelectValue placeholder="Select a deal" />
                </SelectTrigger>
                <SelectContent>
                  {eligibleDeals.map((deal) => (
                    <SelectItem key={deal.id} value={deal.id}>
                      {deal.campaignName} — {deal.counterpartyName} (
                      {formatCurrency(deal.dealValue, deal.currency)})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="dispute-reason">Reason</Label>
              <Textarea
                id="dispute-reason"
                value={reason}
                onChange={(e) => onReasonChange(e.target.value)}
                placeholder="Describe what went wrong and what you need reviewed…"
                rows={4}
                maxLength={2000}
                disabled={submitting}
              />
              <p className="text-xs text-muted-foreground">
                At least 10 characters. Admins use this to triage your case.
              </p>
            </div>

            {submitError && (
              <Alert variant="destructive">
                <AlertTriangle className="h-4 w-4" />
                <AlertTitle>Could not open dispute</AlertTitle>
                <AlertDescription>{submitError}</AlertDescription>
              </Alert>
            )}

            {submitSuccess && (
              <Alert className="border-emerald-300 bg-emerald-50">
                <AlertTitle className="text-emerald-900">Dispute opened</AlertTitle>
                <AlertDescription className="text-emerald-800">{submitSuccess}</AlertDescription>
              </Alert>
            )}

            <Button type="submit" disabled={!canSubmit}>
              {submitting ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />
                  Opening…
                </>
              ) : (
                'Open dispute'
              )}
            </Button>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function DisputeCard({ dispute }: { dispute: CreatorDisputeRow }) {
  const lifecycle = dispute.disputeStatus;
  const badgeLabel = lifecycle ? LIFECYCLE_LABEL[lifecycle] : 'Disputed';

  return (
    <Card className="border-border/80">
      <CardHeader className="pb-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate font-medium">{dispute.campaignName}</p>
            <p className="truncate text-sm text-muted-foreground">
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
          <p className="text-sm leading-relaxed text-foreground/90">{dispute.reason}</p>
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
            <Link to={`/creator/chat?deal=${dispute.collaborationId}`}>
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
        When you open a dispute on a funded deal, it will show up here so you can track status and
        resolution.
      </p>
    </div>
  );
}
