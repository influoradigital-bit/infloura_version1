/**
 * INFLUORA ADMIN PANEL — Platform Fee Control Panel
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (finance module)
 *
 * Revenue-critical control surface for the Swapnil-approved platform fee
 * ruling (SHARED_CONTEXT.md "Fee config (P0)": 10% brand / 15% creator,
 * platform absorbs Razorpay's payment processing cost — Option A). Shows the
 * live schedule, a plainly-labeled processing-cost-absorption indicator, a
 * SUPER_ADMIN-only edit form with 0–100% + mandatory-reason validation, and
 * a change-history audit table.
 *
 * Data comes from `useFeeConfig()` (src/admin/hooks/useFeeConfig.ts), backed
 * by the live `PlatformFeeAdminController` (Vikram). Submit calls
 * `financeApi.updateFeeConfig()` directly (src/admin/services/api-contracts.ts).
 * Same overall pattern as BrandProfile.tsx / CreatorProfile.tsx (mandatory
 * reason field, AlertDialog confirmation).
 *
 * OPTIMISTIC LOCK: submits `expectedEffectiveDate` (the `effectiveDate` this
 * admin last loaded) as a concurrency token — see
 * `PlatformFeeUpdateRequest.expectedEffectiveDate` in admin.types.ts. Server-
 * side enforcement of that token is NOT yet wired (flagged for Vikram in
 * api-contracts.ts); today the only real guard is the backend's
 * `PlatformFeeConfig#version` (Hibernate `@Version`), checked entirely
 * inside `PlatformFeeAdminService#update`'s own load-then-save transaction —
 * which only 409s when two PUTs race within that window, not the realistic
 * "two SUPER_ADMINs minutes apart" case. Either way, any failure (including
 * a genuine 409 once enforcement lands) is handled the same: the error is
 * surfaced, the (now-stale) form is not submitted, and `refresh()` pulls the
 * real current values so the admin edits from a fresh baseline.
 *
 * ROLE GATING: client-side role checks are a UX affordance only (see
 * useAdminAuth.ts's own security note) — the server (`@PreAuthorize` on
 * `PlatformFeeAdminController`) is the real enforcement boundary. This
 * component hides/disables the edit form for non-SUPER_ADMIN roles so
 * ADMIN/SUPPORT never see an editable form they can't actually submit.
 *
 * Because a fee-schedule change is a blast-radius mutation (every brand
 * invoice and creator payout going forward), the submit flow requires a
 * second, explicit confirmation step: validating the form does not submit
 * it — it opens a confirmation dialog that spells out the before/after
 * percentages and their plain-English effect before anything is sent.
 */

import { useEffect, useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Wallet,
  Percent,
  ShieldCheck,
  ShieldAlert,
  Lock,
  History,
  CalendarDays,
  UserRound,
  ArrowRight,
  type LucideIcon,
} from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from '@/components/ui/table';
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import { useFeeConfig } from '../../hooks/useFeeConfig';
import { useAdminAuth } from '../../hooks/useAdminAuth';
import { auditAction, AuditAction } from '../../utils/auditLogger';
import { financeApi } from '../../services/api-contracts';
import KpiCard from '../dashboard/KpiCard';
import { AdminRole } from '../../types/admin.types';
import type { PlatformFeeConfig } from '../../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatPercent(value: number): string {
  // Trim to at most 1 decimal place without trailing zeros (e.g. 10, 12.5).
  return `${Number(value.toFixed(2)).toString().replace(/\.0$/, '')}%`;
}

function formatDate(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(iso));
  } catch {
    return 'Invalid date';
  }
}

function formatDateTime(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  } catch {
    return 'Invalid date';
  }
}

// ============================================
// STATUS PILL — solid, AA-legible fill (not a washed-out pastel badge),
// same pattern as KpiCard / BrandProfile / CreatorProfile per prior
// brand-CTA-contrast feedback.
// ============================================

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({
  tone,
  icon: Icon,
  children,
}: {
  tone: PillTone;
  icon: LucideIcon;
  children: React.ReactNode;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold text-white',
        PILL_TONE_CLASSES[tone],
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {children}
    </span>
  );
}

// ============================================
// VALIDATION
// ============================================

const feeConfigSchema = z.object({
  brandFeePercent: z.coerce
    .number({ invalid_type_error: 'Enter a number' })
    .min(0, 'Must be at least 0%')
    .max(100, 'Must be at most 100%'),
  creatorFeePercent: z.coerce
    .number({ invalid_type_error: 'Enter a number' })
    .min(0, 'Must be at least 0%')
    .max(100, 'Must be at most 100%'),
  razorpayAbsorbedByPlatform: z.boolean(),
  // Required: every fee change is a revenue-critical mutation and MUST carry
  // an auditable reason (same mandatory-reason pattern as BrandProfile's
  // suspend/reject flows and CreatorProfile's application-reject flow).
  reason: z
    .string()
    .trim()
    .min(10, 'Provide a reason of at least 10 characters for the audit trail'),
});

type FeeConfigFormValues = z.infer<typeof feeConfigSchema>;

/** Builds the plain-English "what this does" bullets for the confirm dialog. */
function describeChange(current: PlatformFeeConfig, next: FeeConfigFormValues): string[] {
  const lines: string[] = [];

  if (next.brandFeePercent !== current.brandFeePercent) {
    const delta = Math.abs(next.brandFeePercent - current.brandFeePercent);
    const dir = next.brandFeePercent > current.brandFeePercent ? 'higher' : 'lower';
    lines.push(
      `Brand fee will be stored as ${formatPercent(delta)} ${dir} and recorded in the audit trail immediately — but brand campaign-spend escrow funding is not yet wired to this rate, so brands will not actually be charged differently until that billing path ships.`,
    );
  }

  if (next.creatorFeePercent !== current.creatorFeePercent) {
    const delta = Math.abs(next.creatorFeePercent - current.creatorFeePercent);
    const dir = next.creatorFeePercent > current.creatorFeePercent ? 'more' : 'less';
    lines.push(
      `Creators will see ${formatPercent(delta)} ${dir} deducted from future payouts, effective immediately. Payouts already queued are not retroactively affected.`,
    );
  }

  if (next.razorpayAbsorbedByPlatform !== current.razorpayAbsorbedByPlatform) {
    lines.push(
      next.razorpayAbsorbedByPlatform
        ? 'The platform will start absorbing Razorpay payment processing costs instead of passing them to brands/creators.'
        : 'Razorpay payment processing costs will start being passed to brands/creators instead of being absorbed by the platform.',
    );
  }

  if (lines.length === 0) {
    lines.push('No percentage or absorption change — only the audit trail reason will be recorded.');
  }

  return lines;
}

// ============================================
// PROPS
// ============================================

export interface FeeControlPanelProps {
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function FeeControlPanel({ className }: FeeControlPanelProps) {
  const { data: feeConfig, history, isLoading, error, refresh } = useFeeConfig();
  const { user, role } = useAdminAuth();
  const isSuperAdmin = role === AdminRole.SUPER_ADMIN;

  const [actionNote, setActionNote] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [pendingChange, setPendingChange] = useState<FeeConfigFormValues | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const form = useForm<FeeConfigFormValues>({
    resolver: zodResolver(feeConfigSchema),
    defaultValues: {
      brandFeePercent: 0,
      creatorFeePercent: 0,
      razorpayAbsorbedByPlatform: true,
      reason: '',
    },
  });
  // Destructured directly off the `useForm()` return value — this is the only reference
  // react-hook-form actually documents as stable across renders. `form` itself is a fresh
  // object every render, so `form.reset` is NOT guaranteed stable and must never go in a
  // dependency array (it would re-trigger the effect below every render -> reset -> re-render
  // loop).
  const { reset } = form;

  // Seed the form once the (live) config resolves. Reason is intentionally
  // left blank each time — a stale reason from a prior edit should never
  // carry over to a new change.
  useEffect(() => {
    if (!feeConfig) return;
    reset({
      brandFeePercent: feeConfig.brandFeePercent,
      creatorFeePercent: feeConfig.creatorFeePercent,
      razorpayAbsorbedByPlatform: feeConfig.razorpayAbsorbedByPlatform,
      reason: '',
    });
  }, [feeConfig, reset]);

  // --------------------------------------------
  // Submit flow. Validating the form only opens the confirm dialog; nothing
  // is sent until the admin explicitly confirms the plain-English
  // implication. The actual PUT happens in handleConfirmSubmit.
  // --------------------------------------------

  function onValidSubmit(values: FeeConfigFormValues) {
    setActionNote(null);
    setSubmitError(null);
    setPendingChange(values);
    setConfirmOpen(true);
  }

  async function handleConfirmSubmit() {
    if (!pendingChange || !feeConfig || isSubmitting) return;

    setIsSubmitting(true);
    setSubmitError(null);

    const result = await financeApi.updateFeeConfig({
      brandFeePercent: pendingChange.brandFeePercent,
      creatorFeePercent: pendingChange.creatorFeePercent,
      razorpayAbsorbedByPlatform: pendingChange.razorpayAbsorbedByPlatform,
      reason: pendingChange.reason,
      // Optimistic-concurrency token — the effectiveDate this admin's form was seeded from.
      // See PlatformFeeUpdateRequest.expectedEffectiveDate (admin.types.ts) for why this isn't
      // fully enforced server-side yet.
      expectedEffectiveDate: feeConfig.effectiveDate,
    });

    if (!result.success) {
      // Optimistic-lock conflict (HTTP 409 / FEE_CONFIG_CONFLICT): another
      // SUPER_ADMIN saved a change since this admin loaded the page. Never
      // retry/overwrite silently — surface it plainly, pull the real
      // current values (the effect below re-seeds the form once `feeConfig`
      // updates), and require the admin to re-review and resubmit.
      setSubmitError(
        result.conflict
          ? 'This fee config was changed by another admin since you loaded it. Reloading the current values — please review and resubmit your change.'
          : (result.error ?? 'Failed to update platform fee configuration.'),
      );
      setIsSubmitting(false);
      setConfirmOpen(false);
      setPendingChange(null);
      if (result.conflict) refresh();
      return;
    }

    if (user) {
      void auditAction(user.id, AuditAction.UPDATE, 'PLATFORM_FEE_CONFIG', 'platform-fee-config', {
        oldValue: {
          brandFeePercent: feeConfig.brandFeePercent,
          creatorFeePercent: feeConfig.creatorFeePercent,
          razorpayAbsorbedByPlatform: feeConfig.razorpayAbsorbedByPlatform,
        },
        newValue: {
          brandFeePercent: pendingChange.brandFeePercent,
          creatorFeePercent: pendingChange.creatorFeePercent,
          razorpayAbsorbedByPlatform: pendingChange.razorpayAbsorbedByPlatform,
        },
        reason: pendingChange.reason,
      });
    }

    setActionNote('Platform fee configuration updated. The new rates are effective immediately.');
    setIsSubmitting(false);
    setConfirmOpen(false);
    setPendingChange(null);
    refresh();
  }

  // --------------------------------------------

  if (isLoading) {
    return (
      <div className={cn('flex flex-col gap-6', className)}>
        <Card className="p-5">
          <div className="h-6 w-56 animate-pulse rounded bg-muted" />
          <div className="mt-3 h-4 w-72 animate-pulse rounded bg-muted" />
        </Card>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <KpiCard title="" value="" isLoading />
          <KpiCard title="" value="" isLoading />
          <KpiCard title="" value="" isLoading />
        </div>
      </div>
    );
  }

  if (error || !feeConfig) {
    return (
      <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load platform fee configuration{error ? `: ${error}` : '.'}
      </Card>
    );
  }

  const changeSummary = pendingChange ? describeChange(feeConfig, pendingChange) : [];

  return (
    <div className={cn('flex flex-col gap-6', className)}>
      {/* Header */}
      <Card className="gap-3 p-5">
        <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
          <div className="flex items-start gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
              <Percent className="size-5" aria-hidden="true" />
            </span>
            <div>
              <h2 className="text-lg font-semibold text-foreground sm:text-xl">Platform Fee Configuration</h2>
              <p className="text-sm text-muted-foreground">
                Take rate applied to brand campaign spend and creator payouts platform-wide.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant="outline">
              <CalendarDays className="size-3.5" aria-hidden="true" />
              Effective {formatDate(feeConfig.effectiveDate)}
            </Badge>
          </div>
        </div>
      </Card>

      {/* Current schedule + processing-cost indicator */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <KpiCard title="Brand Fee" value={formatPercent(feeConfig.brandFeePercent)} icon="finance" />
        <KpiCard title="Creator Fee" value={formatPercent(feeConfig.creatorFeePercent)} icon="finance" />

        <Card className="gap-3 p-5">
          <div className="flex items-start justify-between">
            <span className="text-sm font-medium text-muted-foreground">Payment Processing Cost</span>
            <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
              <Wallet className="size-4.5" aria-hidden="true" />
            </span>
          </div>
          {feeConfig.razorpayAbsorbedByPlatform ? (
            <StatusPill tone="success" icon={ShieldCheck}>
              Platform absorbs Razorpay costs
            </StatusPill>
          ) : (
            <StatusPill tone="warning" icon={ShieldAlert}>
              Passed to brands / creators
            </StatusPill>
          )}
          <p className="text-xs text-muted-foreground">
            {feeConfig.razorpayAbsorbedByPlatform
              ? 'Brands and creators are never charged an extra transaction fee on top of the platform fee — Sage Digital covers Razorpay\'s processing cost.'
              : 'Razorpay\'s payment processing cost is passed through to brands/creators separately from the platform fee.'}
          </p>
        </Card>
      </div>

      {/* Edit form — SUPER_ADMIN only */}
      <Card className="gap-4 p-5">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-foreground">Edit Fee Schedule</h3>
          {!isSuperAdmin && (
            <Badge variant="outline">
              <Lock className="size-3.5" aria-hidden="true" />
              Super Admin only
            </Badge>
          )}
        </div>

        {actionNote && (
          <p className="rounded-lg border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
            {actionNote}
          </p>
        )}

        {submitError && (
          <p
            role="alert"
            className="rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 px-3 py-2 text-xs text-destructive-foreground"
          >
            {submitError}
          </p>
        )}

        {!isSuperAdmin ? (
          <div className="flex items-start gap-3 rounded-lg border border-border bg-muted px-4 py-3 text-sm text-muted-foreground">
            <Lock className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            <p>
              Only Super Admins can change platform fees. The schedule above is the live rate — contact a Super
              Admin if it needs to change.
            </p>
          </div>
        ) : (
          <form
            onSubmit={form.handleSubmit(onValidSubmit)}
            className="flex flex-col gap-4"
            aria-label="Edit platform fee schedule"
          >
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="brandFeePercent">Brand Fee (%)</Label>
                <Input
                  id="brandFeePercent"
                  type="number"
                  min={0}
                  max={100}
                  step="0.1"
                  inputMode="decimal"
                  disabled={isSubmitting}
                  aria-invalid={!!form.formState.errors.brandFeePercent}
                  {...form.register('brandFeePercent')}
                />
                {form.formState.errors.brandFeePercent && (
                  <p className="text-xs text-destructive-foreground">
                    {form.formState.errors.brandFeePercent.message}
                  </p>
                )}
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="creatorFeePercent">Creator Fee (%)</Label>
                <Input
                  id="creatorFeePercent"
                  type="number"
                  min={0}
                  max={100}
                  step="0.1"
                  inputMode="decimal"
                  disabled={isSubmitting}
                  aria-invalid={!!form.formState.errors.creatorFeePercent}
                  {...form.register('creatorFeePercent')}
                />
                {form.formState.errors.creatorFeePercent && (
                  <p className="text-xs text-destructive-foreground">
                    {form.formState.errors.creatorFeePercent.message}
                  </p>
                )}
              </div>
            </div>

            <div className="flex items-center justify-between rounded-lg border border-border px-4 py-3">
              <div>
                <Label htmlFor="razorpayAbsorbedByPlatform">Platform absorbs payment processing costs</Label>
                <p className="text-xs text-muted-foreground">
                  When on, Sage Digital covers Razorpay's processing cost instead of passing it to brands/creators.
                </p>
              </div>
              <Controller
                control={form.control}
                name="razorpayAbsorbedByPlatform"
                render={({ field }) => (
                  <Switch
                    id="razorpayAbsorbedByPlatform"
                    checked={field.value}
                    onCheckedChange={field.onChange}
                    disabled={isSubmitting}
                    aria-label="Platform absorbs payment processing costs"
                  />
                )}
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="reason">Reason (required for audit trail)</Label>
              <Textarea
                id="reason"
                placeholder="e.g. CEO-approved rate cut to stay competitive with rival creator platforms"
                disabled={isSubmitting}
                aria-invalid={!!form.formState.errors.reason}
                {...form.register('reason')}
              />
              {form.formState.errors.reason && (
                <p className="text-xs text-destructive-foreground">{form.formState.errors.reason.message}</p>
              )}
            </div>

            <div>
              {/* No `disabled={isSubmitting}` here: this button only opens the confirm dialog
                  (onValidSubmit), it never performs the mutation itself. The actual PUT is
                  guarded by the `isSubmitting` check in handleConfirmSubmit, and the Confirm
                  button in the dialog below is already disabled while submitting — gating this
                  one too just blocked re-opening/re-reviewing the form for no safety benefit. */}
              <Button type="submit">
                <Percent aria-hidden="true" />
                Review Fee Change
              </Button>
            </div>
          </form>
        )}
      </Card>

      {/* Confirm dialog — spells out the before/after + plain-English implication */}
      <AlertDialog
        open={confirmOpen}
        onOpenChange={(open: boolean) => {
          if (isSubmitting) return;
          setConfirmOpen(open);
          if (!open) setPendingChange(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Confirm platform fee change?</AlertDialogTitle>
            <AlertDialogDescription>
              This changes the take rate applied to every brand and creator on the platform. Review carefully before
              confirming.
            </AlertDialogDescription>
          </AlertDialogHeader>

          {pendingChange && (
            <div className="flex flex-col gap-3 text-sm">
              <div className="flex flex-col gap-2 rounded-lg border border-border p-3">
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Brand Fee</span>
                  <span className="flex items-center gap-1.5 font-medium text-foreground">
                    {formatPercent(feeConfig.brandFeePercent)}
                    <ArrowRight className="size-3.5 text-muted-foreground" aria-hidden="true" />
                    {formatPercent(pendingChange.brandFeePercent)}
                  </span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Creator Fee</span>
                  <span className="flex items-center gap-1.5 font-medium text-foreground">
                    {formatPercent(feeConfig.creatorFeePercent)}
                    <ArrowRight className="size-3.5 text-muted-foreground" aria-hidden="true" />
                    {formatPercent(pendingChange.creatorFeePercent)}
                  </span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Processing Cost Absorption</span>
                  <span className="font-medium text-foreground">
                    {feeConfig.razorpayAbsorbedByPlatform ? 'Platform' : 'Brand/Creator'}
                    <ArrowRight className="mx-1.5 inline size-3.5 text-muted-foreground" aria-hidden="true" />
                    {pendingChange.razorpayAbsorbedByPlatform ? 'Platform' : 'Brand/Creator'}
                  </span>
                </div>
              </div>

              <ul className="flex list-disc flex-col gap-1.5 pl-5 text-muted-foreground">
                {changeSummary.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>

              <div className="rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
                <span className="font-medium text-foreground">Reason: </span>
                {pendingChange.reason}
              </div>
            </div>
          )}

          <AlertDialogFooter>
            <AlertDialogCancel disabled={isSubmitting}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={isSubmitting}
              onClick={(e) => {
                e.preventDefault();
                void handleConfirmSubmit();
              }}
            >
              {isSubmitting ? 'Saving…' : 'Confirm Fee Change'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Change history */}
      <Card className="gap-3 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <History className="size-4" aria-hidden="true" />
          Change History
        </h3>
        {history.length === 0 ? (
          <p className="text-sm text-muted-foreground">No fee changes on file.</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Changed</TableHead>
                  <TableHead>Brand Fee</TableHead>
                  <TableHead>Creator Fee</TableHead>
                  <TableHead>Processing Cost</TableHead>
                  <TableHead>Reason</TableHead>
                  <TableHead>Changed By</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatDateTime(entry.changedAt)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {formatPercent(entry.previousBrandFeePercent)}
                      <ArrowRight className="mx-1 inline size-3 text-muted-foreground" aria-hidden="true" />
                      {formatPercent(entry.brandFeePercent)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {formatPercent(entry.previousCreatorFeePercent)}
                      <ArrowRight className="mx-1 inline size-3 text-muted-foreground" aria-hidden="true" />
                      {formatPercent(entry.creatorFeePercent)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {entry.razorpayAbsorbedByPlatform ? (
                        <Badge variant="secondary">Platform</Badge>
                      ) : (
                        <Badge variant="outline">Brand/Creator</Badge>
                      )}
                    </TableCell>
                    <TableCell className="max-w-xs whitespace-normal text-muted-foreground">
                      {entry.reason}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      <span className="flex items-center gap-1.5 text-muted-foreground">
                        <UserRound className="size-3.5" aria-hidden="true" />
                        {entry.changedBy}
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Card>
    </div>
  );
}
