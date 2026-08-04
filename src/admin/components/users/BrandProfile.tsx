/**
 * INFLUORA ADMIN PANEL — Brand Profile (admin-facing)
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P1)
 *
 * Admin detail view for a single brand: company info, KYC/verification
 * status, active campaign count, spend-to-date, account status, and an
 * actions area (verify KYC / suspend-reinstate / contact).
 *
 * Data comes from `useBrandDetail()` (src/admin/hooks/useBrandDetail.ts),
 * live-wired to `brandApi.getById()` (P1-WIRE-3). The action handlers below
 * call the real `brandApi.verifyKyc` / `.suspend` / `.reinstate` mutations
 * from `src/admin/services/api-contracts.ts` and call the hook's `refresh()`
 * on success to re-pull the record; failures surface via local `actionError`
 * state rather than a toast system.
 *
 * SECURITY: this is UX only — the server (`AdminContextService` role + MFA
 * gate) is the real authority for these mutations. KYC/compliance-sensitive;
 * goes to Kabir for review.
 */

import { useState, type ReactNode } from 'react';
import {
  Building2,
  Mail,
  ShieldCheck,
  ShieldQuestion,
  ShieldX,
  Ban,
  CheckCircle2,
  Users,
  FileText,
  CalendarDays,
  Pencil,
  X,
  Wallet,
  ArrowRight,
  type LucideIcon,
} from 'lucide-react';
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import { useBrandDetail } from '../../hooks/useBrandDetail';
import { brandApi } from '../../services/api-contracts';
import KpiCard from '../dashboard/KpiCard';
import { KycStatus } from '../../types/admin.types';
import type { Brand } from '../../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function formatCompactINR(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(amount);
}

function formatDate(iso: string): string {
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(iso));
}

// ============================================
// STATUS PILL — solid, AA-legible fill (not a washed-out pastel badge),
// same pattern as KpiCard's trend pill per prior brand-CTA-contrast feedback.
// ============================================

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({ tone, icon: Icon, children }: { tone: PillTone; icon: LucideIcon; children: ReactNode }) {
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

function kycPillProps(status: KycStatus): { tone: PillTone; icon: LucideIcon; label: string } {
  switch (status) {
    case KycStatus.APPROVED:
      return { tone: 'success', icon: ShieldCheck, label: 'KYC Verified' };
    case KycStatus.REJECTED:
      return { tone: 'destructive', icon: ShieldX, label: 'KYC Rejected' };
    case KycStatus.PENDING:
    default:
      return { tone: 'warning', icon: ShieldQuestion, label: 'KYC Pending' };
  }
}

// ============================================
// PROPS
// ============================================

export interface BrandProfileProps {
  /** Brand id to load and display. */
  brandId: string;
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function BrandProfile({ brandId, className }: BrandProfileProps) {
  const { data: brand, isLoading, error, refresh } = useBrandDetail(brandId);
  const [actionNote, setActionNote] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [suspendReason, setSuspendReason] = useState('');
  const [kycRejectReason, setKycRejectReason] = useState('');
  const [kycApproveReason, setKycApproveReason] = useState('');
  const [reinstateReason, setReinstateReason] = useState('');

  // Controlled open state per confirm dialog — kept open during actionLoading
  // and closed programmatically only on success, so the admin sees the
  // in-dialog error on failure instead of the dialog vanishing mid-request.
  const [approveKycOpen, setApproveKycOpen] = useState(false);
  const [rejectKycOpen, setRejectKycOpen] = useState(false);
  const [suspendOpen, setSuspendOpen] = useState(false);
  const [reinstateOpen, setReinstateOpen] = useState(false);

  // --------------------------------------------
  // Edit mode — only the backend's allow-listed fields for `PUT
  // /admin/brands/{id}` (`AdminBrandDtos.UpdateBrandRequest`): name, industry,
  // size, email. KYC status, suspension, and money fields are NOT editable
  // here — they have their own dedicated, audited mutations above.
  // --------------------------------------------
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState('');
  const [editIndustry, setEditIndustry] = useState('');
  const [editSize, setEditSize] = useState<Brand['size']>('SMB');
  const [editEmail, setEditEmail] = useState('');
  const [editError, setEditError] = useState<string | null>(null);
  const [editLoading, setEditLoading] = useState(false);

  // --------------------------------------------
  // Override campaign budget — money-moving path. Deliberately gated behind
  // an explicit confirm dialog (not just the inline campaign picker) with a
  // required reason, same "second confirmation step" pattern as
  // FeeControlPanel.tsx's fee-schedule change.
  // --------------------------------------------
  const [overrideCampaignId, setOverrideCampaignId] = useState('');
  const [overrideBudget, setOverrideBudget] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [overrideError, setOverrideError] = useState<string | null>(null);
  const [overrideLoading, setOverrideLoading] = useState(false);
  const [overrideConfirmOpen, setOverrideConfirmOpen] = useState(false);
  const [overrideNote, setOverrideNote] = useState<string | null>(null);

  // --------------------------------------------
  // Action handlers — live-wired to brandApi (P1-WIRE-3). Server-side
  // role/MFA re-authorization (AdminContextService) is the real authority;
  // these handlers are UX only. Every mutation requires an admin-entered
  // reason (audit trail) — never a fabricated literal.
  // --------------------------------------------

  async function handleApproveKyc() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await brandApi.verifyKyc({ brandId, action: 'APPROVE', reason: kycApproveReason });
    if (res.success) {
      setActionNote('KYC approved.');
      setKycApproveReason('');
      setApproveKycOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to approve KYC.');
    }
    setActionLoading(false);
  }

  async function handleRejectKyc() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await brandApi.verifyKyc({ brandId, action: 'REJECT', reason: kycRejectReason });
    if (res.success) {
      setActionNote('KYC rejected.');
      setKycRejectReason('');
      setRejectKycOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to reject KYC.');
    }
    setActionLoading(false);
  }

  async function handleSuspend() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await brandApi.suspend(brandId, suspendReason);
    if (res.success) {
      setActionNote('Brand suspended.');
      setSuspendReason('');
      setSuspendOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to suspend brand.');
    }
    setActionLoading(false);
  }

  async function handleReinstate() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await brandApi.reinstate(brandId, reinstateReason);
    if (res.success) {
      setActionNote('Brand reinstated.');
      setReinstateReason('');
      setReinstateOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to reinstate brand.');
    }
    setActionLoading(false);
  }

  // --------------------------------------------
  // Edit mode handlers
  // --------------------------------------------

  function startEditing() {
    if (!brand) return;
    setEditName(brand.name);
    setEditIndustry(brand.industry);
    setEditSize(brand.size);
    setEditEmail(brand.email);
    setEditError(null);
    setIsEditing(true);
  }

  function cancelEditing() {
    setIsEditing(false);
    setEditError(null);
  }

  async function handleSaveEdit() {
    setEditLoading(true);
    setEditError(null);
    const res = await brandApi.update(brandId, {
      name: editName.trim(),
      industry: editIndustry.trim(),
      size: editSize,
      email: editEmail.trim(),
    });
    if (res.success) {
      setIsEditing(false);
      refresh();
    } else {
      setEditError(res.error ?? 'Failed to update brand.');
    }
    setEditLoading(false);
  }

  // --------------------------------------------
  // Budget override handlers — money path, requires confirm + reason.
  // --------------------------------------------

  function openOverrideConfirm() {
    setOverrideError(null);
    setOverrideConfirmOpen(true);
  }

  async function handleConfirmOverrideBudget() {
    setOverrideLoading(true);
    setOverrideError(null);
    setOverrideNote(null);
    const budgetValue = Number(overrideBudget);
    const res = await brandApi.overrideBudget(brandId, overrideCampaignId, budgetValue, overrideReason);
    if (res.success) {
      setOverrideNote('Campaign budget override submitted.');
      setOverrideCampaignId('');
      setOverrideBudget('');
      setOverrideReason('');
      setOverrideConfirmOpen(false);
      refresh();
    } else {
      setOverrideError(res.error ?? 'Failed to override campaign budget.');
    }
    setOverrideLoading(false);
  }

  const overrideSelectedCampaign = brand?.campaigns.find((c) => c.id === overrideCampaignId) ?? null;
  const overrideBudgetValue = Number(overrideBudget);
  const overrideBudgetValid =
    overrideCampaignId.length > 0 &&
    overrideBudget.trim().length > 0 &&
    Number.isFinite(overrideBudgetValue) &&
    overrideBudgetValue > 0 &&
    overrideReason.trim().length >= 10;

  // --------------------------------------------

  if (isLoading) {
    return (
      <div className={cn('flex flex-col gap-6', className)}>
        <Card className="p-5">
          <div className="h-6 w-48 animate-pulse rounded bg-muted" />
          <div className="mt-3 h-4 w-64 animate-pulse rounded bg-muted" />
        </Card>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <KpiCard title="" value="" isLoading />
          <KpiCard title="" value="" isLoading />
        </div>
      </div>
    );
  }

  if (error || !brand) {
    return (
      <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load brand profile{error ? `: ${error}` : '.'}
      </Card>
    );
  }

  const activeCampaignCount = brand.campaigns.filter((c) => c.status === 'ACTIVE').length;
  const kycPill = kycPillProps(brand.kycStatus);

  return (
    <div className={cn('flex flex-col gap-6', className)}>
      {/* Header */}
      <Card className="gap-4 p-5">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
          <div className="flex items-start gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
              <Building2 className="size-5" aria-hidden="true" />
            </span>
            <div>
              <h2 className="text-lg font-semibold text-foreground sm:text-xl">{brand.name}</h2>
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Mail className="size-3.5" aria-hidden="true" />
                {brand.email}
              </p>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{brand.industry}</Badge>
                <Badge variant="outline">{brand.size}</Badge>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={kycPill.tone} icon={kycPill.icon}>
              {kycPill.label}
            </StatusPill>
            {brand.isSuspended ? (
              <StatusPill tone="destructive" icon={Ban}>
                Suspended
              </StatusPill>
            ) : (
              <StatusPill tone="success" icon={CheckCircle2}>
                Active
              </StatusPill>
            )}
            {!isEditing && (
              <Button type="button" size="sm" variant="outline" onClick={startEditing}>
                <Pencil aria-hidden="true" />
                Edit
              </Button>
            )}
          </div>
        </div>
      </Card>

      {/* Edit form — only the backend allow-listed fields (name, industry,
          size, email). KYC/suspension/money fields stay read-only here. */}
      {isEditing && (
        <Card className="gap-4 p-5">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-foreground">Edit Brand</h3>
            <Button type="button" size="sm" variant="ghost" onClick={cancelEditing} disabled={editLoading}>
              <X aria-hidden="true" />
              Cancel
            </Button>
          </div>

          {editError && (
            <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
              {editError}
            </p>
          )}

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="brand-edit-name">Company Name</Label>
              <Input id="brand-edit-name" value={editName} onChange={(e) => setEditName(e.target.value)} disabled={editLoading} />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="brand-edit-email">Email</Label>
              <Input
                id="brand-edit-email"
                type="email"
                value={editEmail}
                onChange={(e) => setEditEmail(e.target.value)}
                disabled={editLoading}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="brand-edit-industry">Industry</Label>
              <Input
                id="brand-edit-industry"
                value={editIndustry}
                onChange={(e) => setEditIndustry(e.target.value)}
                disabled={editLoading}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="brand-edit-size">Size</Label>
              <Select value={editSize} onValueChange={(value) => setEditSize(value as Brand['size'])}>
                <SelectTrigger id="brand-edit-size" disabled={editLoading}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="STARTUP">Startup</SelectItem>
                  <SelectItem value="SMB">SMB</SelectItem>
                  <SelectItem value="ENTERPRISE">Enterprise</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div>
            <Button
              type="button"
              size="sm"
              onClick={() => void handleSaveEdit()}
              disabled={editLoading || editName.trim().length === 0 || editEmail.trim().length === 0}
            >
              {editLoading ? 'Saving…' : 'Save Changes'}
            </Button>
          </div>
        </Card>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <KpiCard title="Active Campaigns" value={activeCampaignCount} icon="campaigns" />
        <KpiCard title="Spend to Date" value={formatCompactINR(brand.totalSpend)} icon="revenue" />
      </div>

      {/* Company details */}
      <Card className="gap-4 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <FileText className="size-4" aria-hidden="true" />
          Company Details
        </h3>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted-foreground">GST Number</dt>
            <dd className="font-medium text-foreground">{brand.gstNumber ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">PAN Number</dt>
            <dd className="font-medium text-foreground">{brand.panNumber ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Total Spend</dt>
            <dd className="font-medium text-foreground">{formatINR(brand.totalSpend)}</dd>
          </div>
          <div>
            <dt className="flex items-center gap-1 text-muted-foreground">
              <CalendarDays className="size-3.5" aria-hidden="true" />
              Member Since
            </dt>
            <dd className="font-medium text-foreground">{formatDate(brand.createdAt)}</dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-muted-foreground">Billing Address</dt>
            <dd className="font-medium text-foreground">{brand.billingAddress ?? '—'}</dd>
          </div>
        </dl>
      </Card>

      {/* Team members */}
      <Card className="gap-3 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Users className="size-4" aria-hidden="true" />
          Team Members
        </h3>
        {brand.teamMembers.length === 0 ? (
          <p className="text-sm text-muted-foreground">No team members on file.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {brand.teamMembers.map((member) => (
              <li key={member.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                <div>
                  <p className="font-medium text-foreground">{member.name}</p>
                  <p className="text-muted-foreground">{member.email}</p>
                </div>
                <Badge variant="outline">{member.role}</Badge>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* Override campaign budget — money path. Requires an explicit confirm
          dialog + mandatory reason (>=10 chars, server-enforced) before the
          mutation fires. */}
      <Card className="gap-4 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Wallet className="size-4" aria-hidden="true" />
          Override Campaign Budget
        </h3>
        <p className="text-xs text-muted-foreground">
          Directly sets a campaign's budget, bypassing the brand's normal spend controls. This is a
          money-moving action — recorded on the campaign's audit trail.
        </p>

        {overrideNote && (
          <p className="rounded-lg border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
            {overrideNote}
          </p>
        )}
        {overrideError && !overrideConfirmOpen && (
          <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
            {overrideError}
          </p>
        )}

        {brand.campaigns.length === 0 ? (
          <p className="text-sm text-muted-foreground">This brand has no campaigns to override.</p>
        ) : (
          <>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="override-campaign">Campaign</Label>
                <Select value={overrideCampaignId} onValueChange={setOverrideCampaignId}>
                  <SelectTrigger id="override-campaign" disabled={overrideLoading}>
                    <SelectValue placeholder="Select a campaign" />
                  </SelectTrigger>
                  <SelectContent>
                    {brand.campaigns.map((c) => (
                      <SelectItem key={c.id} value={c.id}>
                        {c.name} ({formatCompactINR(c.budget)})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="override-new-budget">New Budget (INR)</Label>
                <Input
                  id="override-new-budget"
                  type="number"
                  min={1}
                  step="1"
                  inputMode="decimal"
                  value={overrideBudget}
                  onChange={(e) => setOverrideBudget(e.target.value)}
                  disabled={overrideLoading}
                  placeholder="e.g. 150000"
                />
              </div>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="override-reason">Reason (min. 10 characters, required)</Label>
              <Textarea
                id="override-reason"
                value={overrideReason}
                onChange={(e) => setOverrideReason(e.target.value)}
                disabled={overrideLoading}
                placeholder="e.g. Brand requested a mid-campaign budget increase, approved by account manager"
              />
            </div>

            <div>
              <Button type="button" size="sm" variant="destructive" onClick={openOverrideConfirm} disabled={!overrideBudgetValid}>
                <Wallet aria-hidden="true" />
                Review Budget Override
              </Button>
            </div>
          </>
        )}
      </Card>

      {/* Budget override confirm dialog — spells out old -> new before anything is sent. */}
      <AlertDialog
        open={overrideConfirmOpen}
        onOpenChange={(open) => {
          if (overrideLoading) return;
          setOverrideConfirmOpen(open);
          if (!open) setOverrideError(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Confirm campaign budget override?</AlertDialogTitle>
            <AlertDialogDescription>
              This directly changes the campaign's budget. Review carefully before confirming — this is
              a money-moving action.
            </AlertDialogDescription>
          </AlertDialogHeader>

          {overrideSelectedCampaign && (
            <div className="flex flex-col gap-3 text-sm">
              <div className="flex flex-col gap-2 rounded-lg border border-border p-3">
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Campaign</span>
                  <span className="font-medium text-foreground">{overrideSelectedCampaign.name}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Budget</span>
                  <span className="flex items-center gap-1.5 font-medium text-foreground">
                    {formatINR(overrideSelectedCampaign.budget)}
                    <ArrowRight className="size-3.5 text-muted-foreground" aria-hidden="true" />
                    {Number.isFinite(overrideBudgetValue) ? formatINR(overrideBudgetValue) : '—'}
                  </span>
                </div>
              </div>

              <div className="rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
                <span className="font-medium text-foreground">Reason: </span>
                {overrideReason}
              </div>
            </div>
          )}

          {overrideError && (
            <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
              {overrideError}
            </p>
          )}

          <AlertDialogFooter>
            <AlertDialogCancel disabled={overrideLoading}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => {
                e.preventDefault();
                void handleConfirmOverrideBudget();
              }}
              disabled={overrideLoading}
            >
              {overrideLoading ? 'Submitting…' : 'Confirm Budget Override'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Actions */}
      <Card className="gap-4 p-5">
        <h3 className="text-sm font-semibold text-foreground">Actions</h3>

        {actionNote && (
          <p className="rounded-lg border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
            {actionNote}
          </p>
        )}

        {actionError && (
          <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
            {actionError}
          </p>
        )}

        <div className="flex flex-wrap items-center gap-2">
          {brand.kycStatus === KycStatus.PENDING && (
            <>
              <AlertDialog
                open={approveKycOpen}
                onOpenChange={(open) => {
                  setApproveKycOpen(open);
                  if (open) {
                    setKycApproveReason('');
                    setActionError(null);
                  }
                }}
              >
                <AlertDialogTrigger asChild>
                  <Button type="button" size="sm" disabled={actionLoading}>
                    <ShieldCheck aria-hidden="true" />
                    Approve KYC
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>Approve KYC for {brand.name}?</AlertDialogTitle>
                    <AlertDialogDescription>
                      This is recorded against the brand's KYC review trail. Provide a justification for the
                      approval.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <Textarea
                    value={kycApproveReason}
                    onChange={(e) => setKycApproveReason(e.target.value)}
                    placeholder="Justification for approval (e.g. GST/PAN docs verified)"
                  />
                  {actionError && (
                    <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
                      {actionError}
                    </p>
                  )}
                  <AlertDialogFooter>
                    <AlertDialogCancel disabled={actionLoading}>Cancel</AlertDialogCancel>
                    <AlertDialogAction
                      onClick={(e) => {
                        e.preventDefault();
                        void handleApproveKyc();
                      }}
                      disabled={actionLoading || kycApproveReason.trim().length === 0}
                    >
                      {actionLoading ? 'Approving…' : 'Approve KYC'}
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>

              <AlertDialog
                open={rejectKycOpen}
                onOpenChange={(open) => {
                  setRejectKycOpen(open);
                  if (open) {
                    setKycRejectReason('');
                    setActionError(null);
                  }
                }}
              >
                <AlertDialogTrigger asChild>
                  <Button type="button" size="sm" variant="destructive" disabled={actionLoading}>
                    <ShieldX aria-hidden="true" />
                    Reject KYC
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>Reject KYC for {brand.name}?</AlertDialogTitle>
                    <AlertDialogDescription>
                      This is recorded against the brand's KYC review trail. Provide a reason for the rejection.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <Textarea
                    value={kycRejectReason}
                    onChange={(e) => setKycRejectReason(e.target.value)}
                    placeholder="Reason for rejection (e.g. incorporation document expired)"
                  />
                  {actionError && (
                    <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
                      {actionError}
                    </p>
                  )}
                  <AlertDialogFooter>
                    <AlertDialogCancel disabled={actionLoading}>Cancel</AlertDialogCancel>
                    <AlertDialogAction
                      onClick={(e) => {
                        e.preventDefault();
                        void handleRejectKyc();
                      }}
                      disabled={actionLoading || kycRejectReason.trim().length === 0}
                    >
                      {actionLoading ? 'Rejecting…' : 'Reject KYC'}
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            </>
          )}

          {brand.isSuspended ? (
            <AlertDialog
              open={reinstateOpen}
              onOpenChange={(open) => {
                setReinstateOpen(open);
                if (open) {
                  setReinstateReason('');
                  setActionError(null);
                }
              }}
            >
              <AlertDialogTrigger asChild>
                <Button type="button" size="sm" disabled={actionLoading}>
                  <CheckCircle2 aria-hidden="true" />
                  Reinstate Account
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Reinstate {brand.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    The brand will regain access to launch and manage campaigns. Provide a reason for the audit
                    trail.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <Textarea
                  value={reinstateReason}
                  onChange={(e) => setReinstateReason(e.target.value)}
                  placeholder="Reason for reinstatement (e.g. appeal reviewed, docs re-verified)"
                />
                {actionError && (
                  <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
                    {actionError}
                  </p>
                )}
                <AlertDialogFooter>
                  <AlertDialogCancel disabled={actionLoading}>Cancel</AlertDialogCancel>
                  <AlertDialogAction
                    onClick={(e) => {
                      e.preventDefault();
                      void handleReinstate();
                    }}
                    disabled={actionLoading || reinstateReason.trim().length === 0}
                  >
                    {actionLoading ? 'Reinstating…' : 'Reinstate Account'}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          ) : (
            <AlertDialog
              open={suspendOpen}
              onOpenChange={(open) => {
                setSuspendOpen(open);
                if (open) {
                  setSuspendReason('');
                  setActionError(null);
                }
              }}
            >
              <AlertDialogTrigger asChild>
                <Button type="button" size="sm" variant="destructive" disabled={actionLoading}>
                  <Ban aria-hidden="true" />
                  Suspend Account
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Suspend {brand.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    The brand will lose access to launch or manage campaigns until reinstated. Provide a reason for
                    the audit trail.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <Textarea
                  value={suspendReason}
                  onChange={(e) => setSuspendReason(e.target.value)}
                  placeholder="Reason for suspension"
                />
                {actionError && (
                  <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
                    {actionError}
                  </p>
                )}
                <AlertDialogFooter>
                  <AlertDialogCancel disabled={actionLoading}>Cancel</AlertDialogCancel>
                  <AlertDialogAction
                    onClick={(e) => {
                      e.preventDefault();
                      void handleSuspend();
                    }}
                    disabled={actionLoading || suspendReason.trim().length === 0}
                  >
                    {actionLoading ? 'Suspending…' : 'Suspend Account'}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}

          <Button type="button" size="sm" variant="outline" asChild>
            <a href={`mailto:${brand.email}`}>
              <Mail aria-hidden="true" />
              Contact Brand
            </a>
          </Button>
        </div>
      </Card>
    </div>
  );
}
