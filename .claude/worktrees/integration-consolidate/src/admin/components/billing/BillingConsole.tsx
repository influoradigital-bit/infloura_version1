/**
 * INFLUORA ADMIN PANEL — Subscription Billing Console
 * Owner: Ananya (Frontend)
 * Reference: wiki/processes/subscription-billing-task-breakdown.md, Task 25
 *            (Admin billing console). Mounted at /admin/billing by
 *            `src/admin/pages/BillingPage.tsx`.
 *
 * LIVE-WIRED (W6-1): This console now calls the real AdminBillingController
 * (Vikram) endpoints. All mock data has been removed. The backend is FULLY BUILT
 * and this is the production implementation.
 *
 * - MRR/ARR/churn cards: `useBillingMetrics()` → GET /admin/billing/metrics
 * - Subscriptions table: `useBillingSubscriptions()` → GET /admin/billing/subscriptions
 * - Comp Pro modal: `useGrantComp()` → POST /admin/billing/comp
 * - Override modal: `useOverridePlan()` → POST /admin/billing/override
 */

import { useMemo, useState } from 'react';
import {
  CreditCard,
  FlaskConical,
  Gift,
  SlidersHorizontal,
  Wallet,
  CalendarIcon,
  Search,
  AlertTriangle,
} from 'lucide-react';
import { format } from 'date-fns';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from '@/components/ui/table';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Calendar as CalendarComponent } from '@/components/ui/calendar';
import { useToast } from '@/hooks/use-toast';
import { cn } from '@/lib/utils';
import KpiCard from '../dashboard/KpiCard';
import {
  useBillingMetrics,
  useBillingSubscriptions,
  useGrantComp,
  useOverridePlan,
} from '../../hooks/useBillingData';

// ============================================
// STATUS CONSTANTS
// ============================================

const STATUS_FILTER_OPTIONS: { value: string; label: string }[] = [
  { value: 'ALL', label: 'All statuses' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PAST_DUE', label: 'Past Due' },
  { value: 'HALTED', label: 'Halted' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

// ============================================
// FORMATTING
// ============================================

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatDate(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(iso));
  } catch {
    return 'Invalid date';
  }
}

// ============================================
// STATUS PILL — solid, AA-legible fill (not a washed-out pastel badge), same
// pattern as DisputeList/FeeControlPanel/TicketList per prior
// brand-CTA-contrast feedback.
// ============================================

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({ tone, children }: { tone: PillTone; children: React.ReactNode }) {
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

function statusPillTone(status: string): PillTone {
  switch (status.toUpperCase()) {
    case 'ACTIVE':
      return 'success';
    case 'PAST_DUE':
      return 'warning';
    case 'HALTED':
      return 'destructive';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

function statusLabel(status: string): string {
  return status
    .toLowerCase()
    .split('_')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

// ============================================
// ERROR NOTICE — shown when mutations fail
// ============================================

function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
      <p>{message}</p>
    </div>
  );
}

// ============================================
// COMP PRO MODAL
// ============================================

interface CompProModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workspaceOptions: Array<{ workspaceId: string; workspaceName: string }>;
  onSuccess: () => void;
}

function CompProModal({ open, onOpenChange, workspaceOptions, onSuccess }: CompProModalProps) {
  const { toast } = useToast();
  const { grantComp, isGranting, error, reset } = useGrantComp();
  const [workspaceId, setWorkspaceId] = useState('');
  const [reason, setReason] = useState('');
  const [expiryDate, setExpiryDate] = useState<Date | undefined>(undefined);

  const reasonValid = reason.trim().length >= 10;
  const canSubmit = workspaceId !== '' && reasonValid && !isGranting;

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) {
      setWorkspaceId('');
      setReason('');
      setExpiryDate(undefined);
      reset();
    }
    onOpenChange(nextOpen);
  }

  async function handleSubmit() {
    if (!canSubmit) return;

    const result = await grantComp({
      workspaceId,
      planCode: 'PRO',
      reason: reason.trim(),
      expiresAt: expiryDate ? expiryDate.toISOString() : null,
    });

    if (result) {
      toast({
        title: 'Comp granted',
        description: `Pro subscription granted to ${workspaceOptions.find((w) => w.workspaceId === workspaceId)?.workspaceName ?? 'workspace'}.`,
      });
      onSuccess();
      handleClose(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Gift className="size-4.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            Comp Pro Subscription
          </DialogTitle>
          <DialogDescription>
            Grant a workspace complimentary Pro access. Every comp requires a reason for the
            audit trail (`AdminAuditLog`, action=TIER_ADJUST).
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          {error && <ErrorNotice message={error} />}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="comp-workspace">Workspace</Label>
            <Select value={workspaceId} onValueChange={setWorkspaceId} disabled={isGranting}>
              <SelectTrigger id="comp-workspace" aria-label="Select workspace">
                <SelectValue placeholder="Search / select a workspace…" />
              </SelectTrigger>
              <SelectContent>
                {workspaceOptions.map((ws) => (
                  <SelectItem key={ws.workspaceId} value={ws.workspaceId}>
                    {ws.workspaceName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="comp-expiry">Expiry date (optional)</Label>
            <Popover>
              <PopoverTrigger asChild>
                <Button
                  id="comp-expiry"
                  type="button"
                  variant="outline"
                  disabled={isGranting}
                  className={cn('w-full justify-start text-left font-normal', !expiryDate && 'text-muted-foreground')}
                >
                  <CalendarIcon className="mr-2 size-4" aria-hidden="true" />
                  {expiryDate ? format(expiryDate, 'PPP') : 'No expiry — stays comped indefinitely'}
                </Button>
              </PopoverTrigger>
              <PopoverContent className="w-auto p-0" align="start">
                <CalendarComponent
                  mode="single"
                  selected={expiryDate}
                  onSelect={setExpiryDate}
                  disabled={(date) => date < new Date()}
                  initialFocus
                />
              </PopoverContent>
            </Popover>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="comp-reason">Reason (required for audit trail)</Label>
            <Textarea
              id="comp-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Partnership pilot — CEO-approved 90-day Pro comp"
              aria-invalid={reason.trim().length > 0 && !reasonValid}
              disabled={isGranting}
            />
            {reason.trim().length > 0 && !reasonValid && (
              <p className="text-xs text-destructive-foreground">
                Provide a reason of at least 10 characters for the audit trail.
              </p>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button type="button" onClick={handleSubmit} disabled={!canSubmit}>
            <Gift aria-hidden="true" />
            {isGranting ? 'Granting...' : 'Grant Comp Pro'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ============================================
// OVERRIDE MODAL
// ============================================

interface OverrideModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workspaceOptions: Array<{ workspaceId: string; workspaceName: string }>;
  onSuccess: () => void;
}

function OverrideModal({ open, onOpenChange, workspaceOptions, onSuccess }: OverrideModalProps) {
  const { toast } = useToast();
  const { overridePlan, isOverriding, error, reset } = useOverridePlan();
  const [workspaceId, setWorkspaceId] = useState('');
  const [targetPlan, setTargetPlan] = useState('');
  const [reason, setReason] = useState('');

  const reasonValid = reason.trim().length >= 10;
  const canSubmit = workspaceId !== '' && targetPlan !== '' && reasonValid && !isOverriding;

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) {
      setWorkspaceId('');
      setTargetPlan('');
      setReason('');
      reset();
    }
    onOpenChange(nextOpen);
  }

  async function handleSubmit() {
    if (!canSubmit) return;

    const result = await overridePlan({
      workspaceId,
      planCode: targetPlan,
      reason: reason.trim(),
      expiresAt: null,
    });

    if (result) {
      toast({
        title: 'Override applied',
        description: `${workspaceOptions.find((w) => w.workspaceId === workspaceId)?.workspaceName ?? 'Workspace'} reassigned to ${targetPlan}.`,
      });
      onSuccess();
      handleClose(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <SlidersHorizontal className="size-4.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            Override Workspace Plan
          </DialogTitle>
          <DialogDescription>
            Manually reassign a workspace to a different plan. Every override requires a reason
            for the audit trail (`AdminAuditLog`, action=BUDGET_OVERRIDE).
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          {error && <ErrorNotice message={error} />}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="override-workspace">Workspace</Label>
            <Select value={workspaceId} onValueChange={setWorkspaceId} disabled={isOverriding}>
              <SelectTrigger id="override-workspace" aria-label="Select workspace">
                <SelectValue placeholder="Search / select a workspace…" />
              </SelectTrigger>
              <SelectContent>
                {workspaceOptions.map((ws) => (
                  <SelectItem key={ws.workspaceId} value={ws.workspaceId}>
                    {ws.workspaceName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="override-plan">Reassign to plan</Label>
            <Select value={targetPlan} onValueChange={setTargetPlan} disabled={isOverriding}>
              <SelectTrigger id="override-plan" aria-label="Select target plan">
                <SelectValue placeholder="Select a plan…" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="FREE">Free</SelectItem>
                <SelectItem value="PRO">Pro</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              Scope kept generic (plan reassignment only) to match the backend's likely narrow
              first cut — not a per-workspace numeric fee/allotment override.
            </p>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="override-reason">Reason (required for audit trail)</Label>
            <Textarea
              id="override-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Billing dispute resolution — temporarily reassigned to Free pending refund"
              aria-invalid={reason.trim().length > 0 && !reasonValid}
              disabled={isOverriding}
            />
            {reason.trim().length > 0 && !reasonValid && (
              <p className="text-xs text-destructive-foreground">
                Provide a reason of at least 10 characters for the audit trail.
              </p>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button type="button" onClick={handleSubmit} disabled={!canSubmit}>
            <SlidersHorizontal aria-hidden="true" />
            {isOverriding ? 'Applying...' : 'Apply Override'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ============================================
// PROPS
// ============================================

export interface BillingConsoleProps {
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function BillingConsole({ className }: BillingConsoleProps) {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [compModalOpen, setCompModalOpen] = useState(false);
  const [overrideModalOpen, setOverrideModalOpen] = useState(false);

  const metricsResult = useBillingMetrics();
  const subscriptionsResult = useBillingSubscriptions(page, 20, {
    status: statusFilter === 'ALL' ? undefined : statusFilter,
    search: search.trim() || undefined,
  });

  const workspaceOptions = useMemo(() => {
    return (subscriptionsResult.data?.data ?? [])
      .map((sub) => ({
        workspaceId: sub.workspaceId,
        workspaceName: sub.workspaceName,
      }))
      .sort((a, b) => a.workspaceName.localeCompare(b.workspaceName));
  }, [subscriptionsResult.data]);

  function handleActionSuccess() {
    metricsResult.refresh();
    subscriptionsResult.refresh();
  }

  const metrics = metricsResult.data;
  const subscriptions = subscriptionsResult.data?.data ?? [];
  const totalSubscriptions = subscriptionsResult.data?.total ?? 0;

  return (
    <div className={cn('flex flex-col gap-6', className)}>
      {/* Error banners */}
      {metricsResult.error && (
        <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
          <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
          <p>Failed to load metrics: {metricsResult.error}</p>
        </div>
      )}
      {subscriptionsResult.error && (
        <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
          <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
          <p>Failed to load subscriptions: {subscriptionsResult.error}</p>
        </div>
      )}

      {/* MRR / ARR / churn / active-Pro cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard
          title="MRR"
          value={metrics ? formatCurrency(metrics.mrrInr) : '—'}
          icon="revenue"
          isLoading={metricsResult.isLoading}
        />
        <KpiCard
          title="ARR"
          value={metrics ? formatCurrency(metrics.arrInr) : '—'}
          icon="revenue"
          isLoading={metricsResult.isLoading}
        />
        <KpiCard
          title="Churn %"
          value={metrics ? `${metrics.churnPercent.toFixed(1)}%` : '—'}
          icon="finance"
          isLoading={metricsResult.isLoading}
        />
        <KpiCard
          title="Active Pro Subscribers"
          value={metrics ? String(metrics.activeProCount) : '—'}
          icon="users"
          isLoading={metricsResult.isLoading}
        />
      </div>

      {/* Comp / Override actions */}
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" onClick={() => setCompModalOpen(true)}>
          <Gift aria-hidden="true" />
          Comp Pro
        </Button>
        <Button type="button" variant="outline" onClick={() => setOverrideModalOpen(true)}>
          <SlidersHorizontal aria-hidden="true" />
          Override
        </Button>
      </div>

      {/* Subscriptions table */}
      <Card className="gap-4 p-5">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <CreditCard className="size-4" aria-hidden="true" />
            Workspace Subscriptions
          </h3>
        </div>

        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
            <div className="relative sm:max-w-xs">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search by workspace name…"
                className="pl-8"
                aria-label="Search by workspace name"
              />
            </div>

            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="sm:w-48" aria-label="Filter by status">
                <SelectValue placeholder="All statuses" />
              </SelectTrigger>
              <SelectContent>
                {STATUS_FILTER_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <p className="whitespace-nowrap text-sm text-muted-foreground">
            {subscriptions.length} of {totalSubscriptions} workspaces
          </p>
        </div>

        <div className="overflow-x-auto rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Workspace</TableHead>
                <TableHead>Plan</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Current Period</TableHead>
                <TableHead>Seats</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {subscriptionsResult.isLoading ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                    Loading subscriptions...
                  </TableCell>
                </TableRow>
              ) : subscriptions.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                    <div className="flex flex-col items-center gap-2">
                      <Wallet className="size-6 text-muted-foreground/60" aria-hidden="true" />
                      No workspaces match the current filters.
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                subscriptions.map((sub) => (
                  <TableRow key={sub.subscriptionId}>
                    <TableCell className="font-medium text-foreground">
                      {sub.workspaceName}
                      {sub.isComp && (
                        <Badge variant="outline" className="ml-2 text-xs">
                          Comp
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      {sub.planCode === 'PRO' ? (
                        <Badge>Pro</Badge>
                      ) : (
                        <Badge variant="outline">Free</Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      <StatusPill tone={statusPillTone(sub.status)}>{statusLabel(sub.status)}</StatusPill>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatDate(sub.currentPeriodStart)} – {formatDate(sub.currentPeriodEnd)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {sub.seatsPurchased}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </Card>

      <CompProModal
        open={compModalOpen}
        onOpenChange={setCompModalOpen}
        workspaceOptions={workspaceOptions}
        onSuccess={handleActionSuccess}
      />
      <OverrideModal
        open={overrideModalOpen}
        onOpenChange={setOverrideModalOpen}
        workspaceOptions={workspaceOptions}
        onSuccess={handleActionSuccess}
      />
    </div>
  );
}
