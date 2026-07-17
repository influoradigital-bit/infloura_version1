/**
 * INFLUORA ADMIN PANEL — Disputes List + Resolve Modal
 * Owner: Ananya (Frontend)
 * Reference: Task #9 (this cycle). Backs `src/admin/pages/DisputesPage.tsx`.
 *
 * Admin disputes table: dispute ID, campaign, brand, creator, status, created
 * date — filterable by status, same table shell as `TicketList.tsx`/
 * `FlagQueue.tsx` (search + Select filter, sortless here since the backend
 * doesn't expose a sort param). Row click opens a resolve Dialog.
 *
 * IMPORTANT — there is no `GET /admin/disputes/:id` endpoint yet
 * (`AdminDisputeController` only exposes `list()` and `resolve()` — see
 * `disputeApi` in api-contracts.ts), so the modal's "detail" view is the
 * `DisputeSummary` row the admin clicked, not a richer fetched record. Once a
 * resolution is submitted, the full `DisputeDetail` returned by `resolve()`
 * (reason, resolutionNotes, resolvedAt, resolvedByAdminId) is shown as
 * confirmation. This is the honest-gap pattern required by TECH-STACK.md rule 7
 * (no fabricated backend contracts) — same posture as `FlagQueue.tsx`.
 *
 * The resolution form requires `creatorSplitPercent` (0-100) when
 * RESOLVED_SPLIT is chosen — mirrors the server-side requirement in
 * `EscrowService.adminSplitForDispute` (`ResolveDisputeRequest.creatorSplitPercent`,
 * `@DecimalMin(0)/@DecimalMax(100)`). This client-side check is a UX affordance
 * only; the backend validation is the source of truth.
 */

import { type ReactNode, useState } from 'react';
import {
  AlertTriangle,
  Building2,
  CalendarClock,
  CheckCircle2,
  Gavel,
  Scale,
  ScrollText,
  Split,
  UserRound,
} from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { useDisputeList } from '../../hooks/useDisputeList';
import { useDisputeResolve } from '../../hooks/useDisputeResolve';
import { DisputeStatus } from '../../types/admin.types';
import type { DisputeSummary } from '../../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

// ============================================
// STATUS PILL — solid, AA-legible fill (not a washed-out pastel badge), same
// pattern as TicketList/FlagQueue/CampaignTable StatusPill per prior
// brand-CTA-contrast feedback.
// ============================================

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({ tone, children }: { tone: PillTone; children: ReactNode }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold text-white',
        PILL_TONE_CLASSES[tone],
      )}
    >
      {children}
    </span>
  );
}

function statusPillTone(status: DisputeStatus): PillTone {
  switch (status) {
    case DisputeStatus.OPEN:
      return 'destructive';
    case DisputeStatus.UNDER_REVIEW:
      return 'warning';
    case DisputeStatus.RESOLVED_BRAND:
    case DisputeStatus.RESOLVED_CREATOR:
    case DisputeStatus.RESOLVED_SPLIT:
    default:
      return 'success';
  }
}

function statusLabel(status: DisputeStatus | string): string {
  return status
    .toLowerCase()
    .split('_')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

function isResolved(status: DisputeStatus): boolean {
  return (
    status === DisputeStatus.RESOLVED_BRAND ||
    status === DisputeStatus.RESOLVED_CREATOR ||
    status === DisputeStatus.RESOLVED_SPLIT
  );
}

// ============================================
// RESOLVE MODAL
// ============================================

const RESOLUTION_OPTIONS: {
  value: DisputeStatus;
  label: string;
  description: string;
  icon: typeof Scale;
}[] = [
  {
    value: DisputeStatus.RESOLVED_BRAND,
    label: 'Resolve for Brand',
    description: 'Refunds the frozen escrow back to the brand.',
    icon: Building2,
  },
  {
    value: DisputeStatus.RESOLVED_CREATOR,
    label: 'Resolve for Creator',
    description: 'Releases the frozen escrow to the creator.',
    icon: UserRound,
  },
  {
    value: DisputeStatus.RESOLVED_SPLIT,
    label: 'Split Between Both',
    description: 'Splits the frozen escrow by a custom percentage.',
    icon: Split,
  },
];

interface DisputeResolveModalProps {
  dispute: DisputeSummary | null;
  onOpenChange: (open: boolean) => void;
  onResolved: () => void;
}

function DisputeResolveModal({ dispute, onOpenChange, onResolved }: DisputeResolveModalProps) {
  const { data, isResolving, error, resolve, reset } = useDisputeResolve();
  const [resolution, setResolution] = useState<DisputeStatus | null>(null);
  const [notes, setNotes] = useState('');
  const [splitPercentInput, setSplitPercentInput] = useState('');

  const alreadyResolved = dispute ? isResolved(dispute.status) : false;

  const splitPercent = Number(splitPercentInput);
  const splitPercentValid =
    splitPercentInput.trim() !== '' && Number.isFinite(splitPercent) && splitPercent >= 0 && splitPercent <= 100;

  const canSubmit =
    resolution !== null &&
    notes.trim().length > 0 &&
    (resolution !== DisputeStatus.RESOLVED_SPLIT || splitPercentValid);

  function handleClose(open: boolean) {
    if (!open) {
      setResolution(null);
      setNotes('');
      setSplitPercentInput('');
      reset();
    }
    onOpenChange(open);
  }

  async function handleSubmit() {
    if (!dispute || !resolution) return;

    const result = await resolve(dispute.disputeId, {
      resolution,
      notes: notes.trim(),
      ...(resolution === DisputeStatus.RESOLVED_SPLIT ? { creatorSplitPercent: splitPercent } : {}),
    });

    if (result) {
      onResolved();
      setTimeout(() => handleClose(false), 1500);
    }
  }

  return (
    <Dialog open={Boolean(dispute)} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Gavel className="size-4.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            Dispute {dispute?.disputeId ?? ''}
          </DialogTitle>
          <DialogDescription>
            {dispute ? `${dispute.brandName} vs. ${dispute.creatorName}` : 'Dispute detail'}
          </DialogDescription>
        </DialogHeader>

        {dispute && (
          <div className="flex flex-col gap-4">
            {/* Summary — the only "detail" available pre-resolve; there is no
                GET /admin/disputes/:id endpoint (see file header). */}
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
              <dt className="text-muted-foreground">Status</dt>
              <dd>
                <StatusPill tone={statusPillTone(dispute.status)}>{statusLabel(dispute.status)}</StatusPill>
              </dd>
              <dt className="text-muted-foreground">Campaign</dt>
              <dd className="text-foreground">{dispute.campaignId}</dd>
              <dt className="text-muted-foreground">Created</dt>
              <dd className="text-foreground">{formatDateTime(dispute.createdAt)}</dd>
              <dt className="text-muted-foreground">Last updated</dt>
              <dd className="text-foreground">{formatDateTime(dispute.updatedAt)}</dd>
            </dl>

            {/* Successful resolution confirmation — full DisputeDetail from resolve(). */}
            {data && (
              <div className="flex items-start gap-2 rounded-lg border border-success-foreground/30 bg-success-foreground/10 p-3 text-sm text-foreground">
                <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-success-foreground" aria-hidden="true" />
                <div className="flex flex-col gap-0.5">
                  <span className="font-medium">Dispute resolved — escrow settled.</span>
                  <span className="text-muted-foreground">
                    Status: {statusLabel(data.status)}
                    {data.resolvedAt ? ` · Resolved ${formatDateTime(data.resolvedAt)}` : ''}
                  </span>
                </div>
              </div>
            )}

            {error && (
              <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-card p-3 text-sm text-destructive-foreground">
                <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                {error}
              </div>
            )}

            {alreadyResolved && !data ? (
              <p className="rounded-lg border border-border bg-muted px-3 py-2 text-sm text-muted-foreground">
                This dispute has already been resolved — no further action is available.
              </p>
            ) : !data ? (
              <div className="flex flex-col gap-4">
                <div className="flex flex-col gap-2">
                  <Label>Resolution</Label>
                  <RadioGroup
                    value={resolution ?? undefined}
                    onValueChange={(value) => setResolution(value as DisputeStatus)}
                  >
                    {RESOLUTION_OPTIONS.map(({ value, label, description, icon: Icon }) => (
                      <label
                        key={value}
                        htmlFor={`resolution-${value}`}
                        className={cn(
                          'flex cursor-pointer items-start gap-3 rounded-lg border border-border p-3 transition-colors',
                          resolution === value ? 'border-primary bg-primary/5' : 'hover:bg-accent',
                        )}
                      >
                        <RadioGroupItem value={value} id={`resolution-${value}`} className="mt-0.5" />
                        <span className="flex flex-col gap-0.5">
                          <span className="inline-flex items-center gap-1.5 text-sm font-medium text-foreground">
                            <Icon className="size-3.5 shrink-0" aria-hidden="true" />
                            {label}
                          </span>
                          <span className="text-xs text-muted-foreground">{description}</span>
                        </span>
                      </label>
                    ))}
                  </RadioGroup>
                </div>

                {resolution === DisputeStatus.RESOLVED_SPLIT && (
                  <div className="flex flex-col gap-2">
                    <Label htmlFor="creator-split-percent">Creator share (%)</Label>
                    <Input
                      id="creator-split-percent"
                      type="number"
                      inputMode="decimal"
                      min={0}
                      max={100}
                      step="0.01"
                      value={splitPercentInput}
                      onChange={(e) => setSplitPercentInput(e.target.value)}
                      placeholder="e.g. 60"
                      aria-invalid={splitPercentInput.trim() !== '' && !splitPercentValid}
                    />
                    <p className="text-xs text-muted-foreground">
                      Percentage of the frozen escrow released to the creator (0-100); the brand's
                      remainder is derived automatically. Required for a split resolution.
                    </p>
                    {splitPercentInput.trim() !== '' && !splitPercentValid && (
                      <p className="text-xs text-destructive-foreground">Enter a number between 0 and 100.</p>
                    )}
                  </div>
                )}

                <div className="flex flex-col gap-2">
                  <Label htmlFor="dispute-resolution-notes" className="inline-flex items-center gap-1.5">
                    <ScrollText className="size-3.5 shrink-0" aria-hidden="true" />
                    Resolution notes
                  </Label>
                  <Textarea
                    id="dispute-resolution-notes"
                    value={notes}
                    onChange={(e) => setNotes(e.target.value)}
                    placeholder="Reason for this resolution — required for the audit trail."
                  />
                </div>
              </div>
            ) : null}
          </div>
        )}

        <DialogFooter>
          {!alreadyResolved && !data && (
            <Button type="button" onClick={handleSubmit} disabled={!canSubmit || isResolving}>
              {isResolving ? 'Resolving…' : 'Submit Resolution'}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ============================================
// PROPS
// ============================================

export interface DisputeListProps {
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function DisputeList({ className }: DisputeListProps) {
  const { disputes, totalCount, totalPages, isLoading, error, filters, setFilters, page, setPage, refresh } =
    useDisputeList();
  const [selectedDispute, setSelectedDispute] = useState<DisputeSummary | null>(null);

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load disputes: {error}
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-4', className)}>
      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={filters.campaignId ?? ''}
            onChange={(e) => setFilters({ ...filters, campaignId: e.target.value || undefined })}
            placeholder="Filter by campaign ID…"
            className="sm:max-w-xs"
            aria-label="Filter by campaign ID"
          />

          <Select
            value={filters.status ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, status: value === 'ALL' ? undefined : (value as DisputeStatus) })
            }
          >
            <SelectTrigger className="sm:w-48" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value={DisputeStatus.OPEN}>Open</SelectItem>
              <SelectItem value={DisputeStatus.UNDER_REVIEW}>Under Review</SelectItem>
              <SelectItem value={DisputeStatus.RESOLVED_BRAND}>Resolved — Brand</SelectItem>
              <SelectItem value={DisputeStatus.RESOLVED_CREATOR}>Resolved — Creator</SelectItem>
              <SelectItem value={DisputeStatus.RESOLVED_SPLIT}>Resolved — Split</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${disputes.length} of ${totalCount} disputes`}
        </p>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Dispute ID</TableHead>
              <TableHead>Campaign</TableHead>
              <TableHead>Brand</TableHead>
              <TableHead>Creator</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Created</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 6 }).map((__, j) => (
                    <TableCell key={j}>
                      <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : disputes.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Scale className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No disputes match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              disputes.map((dispute) => (
                <TableRow
                  key={dispute.disputeId}
                  onClick={() => setSelectedDispute(dispute)}
                  className="cursor-pointer"
                  tabIndex={0}
                  role="button"
                  aria-label={`Open dispute ${dispute.disputeId}`}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelectedDispute(dispute);
                    }
                  }}
                >
                  <TableCell className="font-medium text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Gavel className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                      {dispute.disputeId}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{dispute.campaignId}</TableCell>
                  <TableCell className="text-foreground">{dispute.brandName}</TableCell>
                  <TableCell className="text-foreground">{dispute.creatorName}</TableCell>
                  <TableCell>
                    <StatusPill tone={statusPillTone(dispute.status)}>{statusLabel(dispute.status)}</StatusPill>
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <CalendarClock className="size-3.5 shrink-0" aria-hidden="true" />
                      {formatDateTime(dispute.createdAt)}
                    </span>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-border pt-3">
          <Button type="button" variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="text-sm text-muted-foreground">
            Page {page} of {totalPages}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={page >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            Next
          </Button>
        </div>
      )}

      <DisputeResolveModal
        dispute={selectedDispute}
        onOpenChange={(open) => {
          if (!open) setSelectedDispute(null);
        }}
        onResolved={refresh}
      />
    </div>
  );
}
