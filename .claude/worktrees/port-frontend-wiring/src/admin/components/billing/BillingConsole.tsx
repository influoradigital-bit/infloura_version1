/**
 * INFLUORA ADMIN PANEL — Subscription Billing Console
 * Owner: Ananya (Frontend)
 * Reference: wiki/processes/subscription-billing-task-breakdown.md, Task 25
 *            (Admin billing console). Mounted at /admin/billing by
 *            `src/admin/pages/BillingPage.tsx`.
 *
 * PREP-WORK / MOCK BOUNDARY — read before touching this file:
 * `AdminBillingController` (Vikram) does not exist yet — per the task
 * breakdown's audit note, only `PlatformFeeAdminController` (fee-config CRUD)
 * is live today; `GET /admin/billing/subscriptions`, `GET /admin/billing/metrics`,
 * `POST /admin/billing/comp`, and `POST /admin/billing/override` are all
 * unbuilt. Same posture as `src/pages/brand-billing-settings.tsx`
 * (mock*, "Coming soon" disabled CTA) and TECH-STACK.md rule 7 ("no
 * fabricated backend contracts" — an honest gap beats silent mock data that
 * looks live).
 *
 * Everything below is demo data, clearly labeled:
 * - MRR/ARR/churn/active-Pro cards and the subscriptions table are static
 *   mock arrays (DEMO_METRICS / MOCK_SUBSCRIPTIONS), not derived from any
 *   API call. A permanent "Demo data" banner sits above them.
 * - The "Comp Pro" and "Override" modals validate the form (workspace
 *   picker + mandatory reason, mirroring the backend's expected
 *   mandatory-reason requirement) but their submit handlers never call an
 *   API — they show an explicit "backend pending Kabir security review"
 *   state (banner + toast) and never claim success.
 *
 * FOLLOW-UP (once Vikram's AdminBillingController clears its own Kabir
 * gate — money-granting admin endpoints always get one): swap
 * MOCK_SUBSCRIPTIONS/DEMO_METRICS for real `useQuery` calls against a new
 * `adminBillingApi` group in api-contracts.ts, and wire the two modal
 * submit handlers to real POSTs. Tracked as the live-wiring follow-up in
 * SHARED_CONTEXT.md.
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

// ============================================
// DEMO DATA TYPES (local — no backend contract exists yet, see file header)
// ============================================

type PlanCode = 'FREE' | 'PRO';
type SubscriptionStatus = 'ACTIVE' | 'PAST_DUE' | 'HALTED' | 'CANCELLED';

interface WorkspaceSubscription {
  workspaceId: string;
  workspaceName: string;
  plan: PlanCode;
  status: SubscriptionStatus;
  currentPeriodStart: string; // ISO date
  currentPeriodEnd: string; // ISO date
  seatsUsed: number;
  seatLimit: number | null; // null = unlimited (Pro)
}

const PRO_PRICE_INR = 4999;

// ============================================
// DEMO DATA — static, illustrative only (see file header). Deliberately NOT
// derived from any live aggregation; the real MRR/ARR/churn once
// AdminBillingController exists will aggregate over every Subscription row
// on the platform, not just the rows visible in the demo table below.
// ============================================

const DEMO_METRICS = {
  mrr: 25 * PRO_PRICE_INR, // 25 active Pro subs x Rs.4,999
  proSubscriberCount: 25,
  churnPercent: 3.2,
};

const MOCK_SUBSCRIPTIONS: WorkspaceSubscription[] = [
  { workspaceId: 'ws_01', workspaceName: 'Kashmiri Saffron Traders', plan: 'PRO', status: 'ACTIVE', currentPeriodStart: '2026-06-14', currentPeriodEnd: '2026-07-14', seatsUsed: 4, seatLimit: 5 },
  { workspaceId: 'ws_02', workspaceName: 'GlowLab Cosmetics', plan: 'PRO', status: 'ACTIVE', currentPeriodStart: '2026-07-01', currentPeriodEnd: '2026-07-31', seatsUsed: 2, seatLimit: 5 },
  { workspaceId: 'ws_03', workspaceName: 'Urban Threads Co.', plan: 'FREE', status: 'ACTIVE', currentPeriodStart: '2026-07-01', currentPeriodEnd: '2026-07-31', seatsUsed: 1, seatLimit: 1 },
  { workspaceId: 'ws_04', workspaceName: 'Nomad Gear', plan: 'PRO', status: 'PAST_DUE', currentPeriodStart: '2026-06-20', currentPeriodEnd: '2026-07-20', seatsUsed: 5, seatLimit: 5 },
  { workspaceId: 'ws_05', workspaceName: 'BrewCraft Coffee', plan: 'FREE', status: 'ACTIVE', currentPeriodStart: '2026-07-05', currentPeriodEnd: '2026-08-04', seatsUsed: 1, seatLimit: 1 },
  { workspaceId: 'ws_06', workspaceName: 'Verve Fitness', plan: 'PRO', status: 'HALTED', currentPeriodStart: '2026-05-28', currentPeriodEnd: '2026-06-27', seatsUsed: 3, seatLimit: 5 },
  { workspaceId: 'ws_07', workspaceName: 'Aarambh Organics', plan: 'FREE', status: 'ACTIVE', currentPeriodStart: '2026-07-10', currentPeriodEnd: '2026-08-09', seatsUsed: 1, seatLimit: 1 },
  { workspaceId: 'ws_08', workspaceName: 'Lumina Skincare', plan: 'PRO', status: 'ACTIVE', currentPeriodStart: '2026-06-30', currentPeriodEnd: '2026-07-30', seatsUsed: 5, seatLimit: 5 },
  { workspaceId: 'ws_09', workspaceName: 'TrailBlaze Outdoors', plan: 'PRO', status: 'CANCELLED', currentPeriodStart: '2026-05-01', currentPeriodEnd: '2026-05-31', seatsUsed: 2, seatLimit: 5 },
  { workspaceId: 'ws_10', workspaceName: 'Spice Route Exports', plan: 'FREE', status: 'ACTIVE', currentPeriodStart: '2026-07-08', currentPeriodEnd: '2026-08-07', seatsUsed: 1, seatLimit: 1 },
  { workspaceId: 'ws_11', workspaceName: 'PixelForge Games', plan: 'PRO', status: 'ACTIVE', currentPeriodStart: '2026-07-02', currentPeriodEnd: '2026-08-01', seatsUsed: 1, seatLimit: 5 },
  { workspaceId: 'ws_12', workspaceName: 'Zephyr Foods', plan: 'FREE', status: 'ACTIVE', currentPeriodStart: '2026-06-25', currentPeriodEnd: '2026-07-25', seatsUsed: 1, seatLimit: 1 },
];

const STATUS_FILTER_OPTIONS: { value: SubscriptionStatus | 'ALL'; label: string }[] = [
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

function statusPillTone(status: SubscriptionStatus): PillTone {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'PAST_DUE':
      return 'warning';
    case 'HALTED':
      return 'destructive';
    case 'CANCELLED':
      return 'neutral';
  }
}

function statusLabel(status: SubscriptionStatus): string {
  return status
    .toLowerCase()
    .split('_')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

// ============================================
// SHARED "COMING SOON" NOTICE — both action modals below hit this. Never a
// silent success: the submit handler makes no network call at all, and the
// UI says so explicitly (banner inside the dialog + toast), mirroring the
// disabled-button + tooltip pattern already used for brand-billing-settings'
// "Upgrade to Pro" placeholder.
// ============================================

const BACKEND_PENDING_MESSAGE =
  'Coming soon — AdminBillingController is not built yet and this action is pending Kabir’s mandatory security review for admin money-granting endpoints. Nothing was submitted or changed.';

function BackendPendingNotice() {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-warning-foreground/30 bg-warning-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warning-foreground" aria-hidden="true" />
      <p>{BACKEND_PENDING_MESSAGE}</p>
    </div>
  );
}

// ============================================
// COMP PRO MODAL
// ============================================

interface CompProModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  workspaceNames: string[];
}

function CompProModal({ open, onOpenChange, workspaceNames }: CompProModalProps) {
  const { toast } = useToast();
  const [workspaceId, setWorkspaceId] = useState('');
  const [reason, setReason] = useState('');
  const [expiryDate, setExpiryDate] = useState<Date | undefined>(undefined);
  const [attempted, setAttempted] = useState(false);

  const reasonValid = reason.trim().length >= 10;
  const canSubmit = workspaceId !== '' && reasonValid;

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) {
      setWorkspaceId('');
      setReason('');
      setExpiryDate(undefined);
      setAttempted(false);
    }
    onOpenChange(nextOpen);
  }

  function handleSubmit() {
    if (!canSubmit) return;
    // No network call — AdminBillingController#POST /admin/billing/comp does not
    // exist yet. See file header + BackendPendingNotice.
    setAttempted(true);
    toast({
      title: 'Coming soon',
      description: 'Backend pending Kabir security review — no comp was granted.',
    });
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
          {attempted && <BackendPendingNotice />}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="comp-workspace">Workspace</Label>
            <Select value={workspaceId} onValueChange={setWorkspaceId}>
              <SelectTrigger id="comp-workspace" aria-label="Select workspace">
                <SelectValue placeholder="Search / select a workspace…" />
              </SelectTrigger>
              <SelectContent>
                {workspaceNames.map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
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
            Grant Comp Pro
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
  workspaceNames: string[];
}

function OverrideModal({ open, onOpenChange, workspaceNames }: OverrideModalProps) {
  const { toast } = useToast();
  const [workspaceId, setWorkspaceId] = useState('');
  const [targetPlan, setTargetPlan] = useState<PlanCode | ''>('');
  const [reason, setReason] = useState('');
  const [attempted, setAttempted] = useState(false);

  const reasonValid = reason.trim().length >= 10;
  const canSubmit = workspaceId !== '' && targetPlan !== '' && reasonValid;

  function handleClose(nextOpen: boolean) {
    if (!nextOpen) {
      setWorkspaceId('');
      setTargetPlan('');
      setReason('');
      setAttempted(false);
    }
    onOpenChange(nextOpen);
  }

  function handleSubmit() {
    if (!canSubmit) return;
    // No network call — AdminBillingController#POST /admin/billing/override does
    // not exist yet. See file header + BackendPendingNotice.
    setAttempted(true);
    toast({
      title: 'Coming soon',
      description: 'Backend pending Kabir security review — no override was applied.',
    });
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
          {attempted && <BackendPendingNotice />}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="override-workspace">Workspace</Label>
            <Select value={workspaceId} onValueChange={setWorkspaceId}>
              <SelectTrigger id="override-workspace" aria-label="Select workspace">
                <SelectValue placeholder="Search / select a workspace…" />
              </SelectTrigger>
              <SelectContent>
                {workspaceNames.map((name) => (
                  <SelectItem key={name} value={name}>
                    {name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="override-plan">Reassign to plan</Label>
            <Select value={targetPlan} onValueChange={(v) => setTargetPlan(v as PlanCode)}>
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
            Apply Override
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
  const [statusFilter, setStatusFilter] = useState<SubscriptionStatus | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [compModalOpen, setCompModalOpen] = useState(false);
  const [overrideModalOpen, setOverrideModalOpen] = useState(false);

  const workspaceNames = useMemo(
    () => MOCK_SUBSCRIPTIONS.map((s) => s.workspaceName).sort((a, b) => a.localeCompare(b)),
    [],
  );

  const filteredSubscriptions = useMemo(() => {
    return MOCK_SUBSCRIPTIONS.filter((s) => {
      if (statusFilter !== 'ALL' && s.status !== statusFilter) return false;
      if (search.trim() && !s.workspaceName.toLowerCase().includes(search.trim().toLowerCase())) return false;
      return true;
    });
  }, [statusFilter, search]);

  return (
    <div className={cn('flex flex-col gap-6', className)}>
      {/* Demo data banner — this whole page is UI-shell-only prep work (Task
          25); AdminBillingController doesn't exist yet, see file header. */}
      <div className="flex items-start gap-2 rounded-lg border border-accent bg-accent/40 p-3 text-sm text-accent-foreground">
        <FlaskConical className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <p>
          <span className="font-semibold">Demo data.</span> MRR/ARR/churn and the subscriptions
          table below are illustrative mock values — `AdminBillingController` hasn't been built
          yet. Live wiring is a follow-up task once the backend clears Kabir's security review.
        </p>
      </div>

      {/* MRR / ARR / churn / active-Pro cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard title="MRR (demo)" value={formatCurrency(DEMO_METRICS.mrr)} icon="revenue" />
        <KpiCard title="ARR (demo)" value={formatCurrency(DEMO_METRICS.mrr * 12)} icon="revenue" />
        <KpiCard
          title="Churn % (demo)"
          value={`${DEMO_METRICS.churnPercent}%`}
          change={-0.4}
          changeType="positive"
          icon="finance"
        />
        <KpiCard
          title="Active Pro Subscribers (demo)"
          value={String(DEMO_METRICS.proSubscriberCount)}
          icon="users"
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

            <Select value={statusFilter} onValueChange={(v) => setStatusFilter(v as SubscriptionStatus | 'ALL')}>
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
            {filteredSubscriptions.length} of {MOCK_SUBSCRIPTIONS.length} workspaces (demo)
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
              {filteredSubscriptions.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                    <div className="flex flex-col items-center gap-2">
                      <Wallet className="size-6 text-muted-foreground/60" aria-hidden="true" />
                      No workspaces match the current filters.
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                filteredSubscriptions.map((sub) => (
                  <TableRow key={sub.workspaceId}>
                    <TableCell className="font-medium text-foreground">{sub.workspaceName}</TableCell>
                    <TableCell>
                      {sub.plan === 'PRO' ? (
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
                      {sub.seatsUsed} / {sub.seatLimit ?? '∞'}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </Card>

      <CompProModal open={compModalOpen} onOpenChange={setCompModalOpen} workspaceNames={workspaceNames} />
      <OverrideModal open={overrideModalOpen} onOpenChange={setOverrideModalOpen} workspaceNames={workspaceNames} />
    </div>
  );
}
