/**
 * INFLUORA ADMIN PANEL — Creator Profile (admin-facing)
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P1)
 *
 * Admin detail view for a single creator, built for moderation: handle/profile
 * info, Instagram verification status, quality score + tier (the admin-surface
 * shape of Vikram's `CreatorScore` domain entity — see notes below), active
 * campaign collaboration count, flagged-content count, account status, and an
 * actions area (approve/reject application, verify Instagram, suspend/reinstate).
 *
 * Data comes from `useCreatorDetail()` (src/admin/hooks/useCreatorDetail.ts),
 * live-wired to `creatorApi.getById()` (P1-WIRE-3). The action handlers below
 * call the real `creatorApi.reviewApplication` / `.suspend` / `.reinstate` /
 * `.forceInstagramReauth` mutations from `src/admin/services/api-contracts.ts`
 * and call the hook's `refresh()` on success to re-pull the record; failures
 * surface via local `actionError` state rather than a toast system. Same
 * pattern as `BrandProfile.tsx`.
 *
 * SECURITY: this is UX only — the server (`AdminContextService` role + MFA
 * gate) is the real authority for these mutations. Moderation/compliance-
 * sensitive; goes to Kabir for review.
 *
 * SCORE/TIER NOTE: `influora-api`'s `CreatorScore` entity (Phase 3 scoring,
 * `com.influora.domain.entity.CreatorScore`) carries the full computed-score
 * breakdown (fake-follower score, engagement consistency, brand-safety score,
 * etc.) written daily by `ScoreCalculationJob`. The admin surface doesn't need
 * that full breakdown — `CreatorDetail.qualityScore` / `.tier` and
 * `.qualityMetrics.overallScore` (already typed in admin.types.ts) are the
 * admin-relevant projection of that concept, so this view renders those
 * rather than inventing a parallel shape.
 */

import { useState, type ReactNode } from 'react';
import {
  UserRound,
  Mail,
  MapPin,
  Instagram,
  BadgeCheck,
  ShieldQuestion,
  ShieldX,
  Ban,
  CheckCircle2,
  Flag,
  Handshake,
  Gauge,
  CalendarDays,
  RefreshCw,
  Pencil,
  X,
  ArrowUpDown,
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
import { useCreatorDetail } from '../../hooks/useCreatorDetail';
import { creatorApi } from '../../services/api-contracts';
import KpiCard from '../dashboard/KpiCard';
import { CreatorApplicationStatus } from '../../types/admin.types';
import type { Creator } from '../../types/admin.types';

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

function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
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

function applicationPillProps(status: CreatorApplicationStatus): { tone: PillTone; icon: LucideIcon; label: string } {
  switch (status) {
    case CreatorApplicationStatus.APPROVED:
      return { tone: 'success', icon: BadgeCheck, label: 'Application Approved' };
    case CreatorApplicationStatus.REJECTED:
      return { tone: 'destructive', icon: ShieldX, label: 'Application Rejected' };
    case CreatorApplicationStatus.PENDING:
    default:
      return { tone: 'warning', icon: ShieldQuestion, label: 'Application Pending' };
  }
}

// ============================================
// PROPS
// ============================================

export interface CreatorProfileProps {
  /** Creator id to load and display. */
  creatorId: string;
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function CreatorProfile({ creatorId, className }: CreatorProfileProps) {
  const { data: creator, isLoading, error, refresh } = useCreatorDetail(creatorId);
  const [actionNote, setActionNote] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [suspendReason, setSuspendReason] = useState('');
  const [applicationRejectReason, setApplicationRejectReason] = useState('');
  const [applicationApproveReason, setApplicationApproveReason] = useState('');
  const [reinstateReason, setReinstateReason] = useState('');

  // Controlled open state per confirm dialog — kept open during actionLoading
  // and closed programmatically only on success, so the admin sees the
  // in-dialog error on failure instead of the dialog vanishing mid-request.
  const [approveApplicationOpen, setApproveApplicationOpen] = useState(false);
  const [rejectApplicationOpen, setRejectApplicationOpen] = useState(false);
  const [suspendOpen, setSuspendOpen] = useState(false);
  const [reinstateOpen, setReinstateOpen] = useState(false);

  // --------------------------------------------
  // Edit mode — only the backend's allow-listed fields for `PUT
  // /admin/creators/{id}` (`AdminCreatorDtos.UpdateCreatorRequest`): name,
  // niche. Application status, suspension, tier, follower/engagement/quality
  // stats, and Instagram verification are NOT editable here — they have
  // their own dedicated, audited mutations (tier adjustment has its own
  // control below; the rest are the existing action buttons).
  // --------------------------------------------
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState('');
  const [editNiche, setEditNiche] = useState('');
  const [editError, setEditError] = useState<string | null>(null);
  const [editLoading, setEditLoading] = useState(false);

  // --------------------------------------------
  // Adjust tier — a moderation/quality-gate action, requires a reason.
  // --------------------------------------------
  const [tierOpen, setTierOpen] = useState(false);
  const [newTier, setNewTier] = useState<Creator['tier']>('NANO');
  const [tierReason, setTierReason] = useState('');
  const [tierError, setTierError] = useState<string | null>(null);
  const [tierLoading, setTierLoading] = useState(false);

  // --------------------------------------------
  // Action handlers — live-wired to creatorApi (P1-WIRE-3). Server-side
  // role/MFA re-authorization (AdminContextService) is the real authority;
  // these handlers are UX only. Every mutation requires an admin-entered
  // reason (audit trail) — never a fabricated literal.
  // --------------------------------------------

  async function handleApproveApplication() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await creatorApi.reviewApplication({
      creatorId,
      action: 'APPROVE',
      reason: applicationApproveReason,
    });
    if (res.success) {
      setActionNote('Application approved.');
      setApplicationApproveReason('');
      setApproveApplicationOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to approve application.');
    }
    setActionLoading(false);
  }

  async function handleRejectApplication() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await creatorApi.reviewApplication({
      creatorId,
      action: 'REJECT',
      reason: applicationRejectReason,
    });
    if (res.success) {
      setActionNote('Application rejected.');
      setApplicationRejectReason('');
      setRejectApplicationOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to reject application.');
    }
    setActionLoading(false);
  }

  async function handleForceInstagramReauth() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await creatorApi.forceInstagramReauth(creatorId);
    if (res.success) {
      setActionNote('Instagram re-auth requested.');
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to force Instagram re-auth.');
    }
    setActionLoading(false);
  }

  async function handleSuspend() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await creatorApi.suspend(creatorId, suspendReason);
    if (res.success) {
      setActionNote('Creator suspended.');
      setSuspendReason('');
      setSuspendOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to suspend creator.');
    }
    setActionLoading(false);
  }

  async function handleReinstate() {
    setActionLoading(true);
    setActionError(null);
    setActionNote(null);
    const res = await creatorApi.reinstate(creatorId, reinstateReason);
    if (res.success) {
      setActionNote('Creator reinstated.');
      setReinstateReason('');
      setReinstateOpen(false);
      refresh();
    } else {
      setActionError(res.error ?? 'Failed to reinstate creator.');
    }
    setActionLoading(false);
  }

  // --------------------------------------------
  // Edit mode handlers
  // --------------------------------------------

  function startEditing() {
    if (!creator) return;
    setEditName(creator.name);
    setEditNiche(creator.niche.join(', '));
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
    const niche = editNiche
      .split(',')
      .map((n) => n.trim())
      .filter((n) => n.length > 0);
    const res = await creatorApi.update(creatorId, { name: editName.trim(), niche });
    if (res.success) {
      setIsEditing(false);
      refresh();
    } else {
      setEditError(res.error ?? 'Failed to update creator.');
    }
    setEditLoading(false);
  }

  // --------------------------------------------
  // Adjust tier handler
  // --------------------------------------------

  async function handleAdjustTier() {
    setTierLoading(true);
    setTierError(null);
    const res = await creatorApi.adjustTier({ creatorId, newTier, reason: tierReason });
    if (res.success) {
      setTierReason('');
      setTierOpen(false);
      refresh();
    } else {
      setTierError(res.error ?? 'Failed to adjust tier.');
    }
    setTierLoading(false);
  }

  // --------------------------------------------

  if (isLoading) {
    return (
      <div className={cn('flex flex-col gap-6', className)}>
        <Card className="p-5">
          <div className="h-6 w-48 animate-pulse rounded bg-muted" />
          <div className="mt-3 h-4 w-64 animate-pulse rounded bg-muted" />
        </Card>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <KpiCard title="" value="" isLoading />
          <KpiCard title="" value="" isLoading />
          <KpiCard title="" value="" isLoading />
        </div>
      </div>
    );
  }

  if (error || !creator) {
    return (
      <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load creator profile{error ? `: ${error}` : '.'}
      </Card>
    );
  }

  const activeCollaborationCount = creator.collaborationHistory.filter((c) => c.status === 'ACTIVE').length;
  const applicationPill = applicationPillProps(creator.applicationStatus);

  return (
    <div className={cn('flex flex-col gap-6', className)}>
      {/* Header */}
      <Card className="gap-4 p-5">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
          <div className="flex items-start gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground">
              <UserRound className="size-5" aria-hidden="true" />
            </span>
            <div>
              <h2 className="text-lg font-semibold text-foreground sm:text-xl">{creator.name}</h2>
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <Mail className="size-3.5" aria-hidden="true" />
                {creator.email}
              </p>
              <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
                <MapPin className="size-3.5" aria-hidden="true" />
                {creator.location}
              </p>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{creator.tier}</Badge>
                {creator.niche.map((n) => (
                  <Badge key={n} variant="outline">
                    {n}
                  </Badge>
                ))}
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={applicationPill.tone} icon={applicationPill.icon}>
              {applicationPill.label}
            </StatusPill>
            {creator.instagramVerified ? (
              <StatusPill tone="success" icon={Instagram}>
                Instagram Verified
              </StatusPill>
            ) : (
              <StatusPill tone="warning" icon={ShieldQuestion}>
                Instagram Unverified
              </StatusPill>
            )}
            {creator.isSuspended ? (
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

      {/* Edit form — only the backend allow-listed fields (name, niche).
          Application status/suspension/tier/verification stay read-only
          here — tier has its own dedicated control below. */}
      {isEditing && (
        <Card className="gap-4 p-5">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-foreground">Edit Creator</h3>
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
              <Label htmlFor="creator-edit-name">Name</Label>
              <Input
                id="creator-edit-name"
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
                disabled={editLoading}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="creator-edit-niche">Niche (comma-separated)</Label>
              <Input
                id="creator-edit-niche"
                value={editNiche}
                onChange={(e) => setEditNiche(e.target.value)}
                disabled={editLoading}
                placeholder="e.g. Beauty, Lifestyle"
              />
            </div>
          </div>

          <div>
            <Button
              type="button"
              size="sm"
              onClick={() => void handleSaveEdit()}
              disabled={editLoading || editName.trim().length === 0}
            >
              {editLoading ? 'Saving…' : 'Save Changes'}
            </Button>
          </div>
        </Card>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <KpiCard title="Creator Score" value={creator.qualityScore} icon="creators" />
        <KpiCard title="Active Collaborations" value={activeCollaborationCount} icon="campaigns" />
        <KpiCard
          title="Flagged Content"
          value={creator.flaggedContentCount}
          changeType={creator.flaggedContentCount > 0 ? 'negative' : 'neutral'}
          icon="moderation"
        />
      </div>

      {/* Profile details */}
      <Card className="gap-4 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Instagram className="size-4" aria-hidden="true" />
          Platform Details
        </h3>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted-foreground">Instagram Handle</dt>
            <dd className="font-medium text-foreground">{creator.instagramHandle}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Followers</dt>
            <dd className="font-medium text-foreground">{formatCompactNumber(creator.followers)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Avg. Reach</dt>
            <dd className="font-medium text-foreground">{formatCompactNumber(creator.platformStats.avgReach)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Engagement Rate</dt>
            <dd className="font-medium text-foreground">{creator.engagementRate}%</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Posts / Reels (30d)</dt>
            <dd className="font-medium text-foreground">
              {creator.platformStats.postsLast30Days} / {creator.platformStats.reelsLast30Days}
            </dd>
          </div>
          <div>
            <dt className="flex items-center gap-1 text-muted-foreground">
              <CalendarDays className="size-3.5" aria-hidden="true" />
              Member Since
            </dt>
            <dd className="font-medium text-foreground">{formatDate(creator.createdAt)}</dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-muted-foreground">Bio</dt>
            <dd className="font-medium text-foreground">{creator.bio}</dd>
          </div>
        </dl>
      </Card>

      {/* Quality / score breakdown */}
      <Card className="gap-4 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Gauge className="size-4" aria-hidden="true" />
          Quality &amp; Score
        </h3>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted-foreground">Overall Score</dt>
            <dd className="font-medium text-foreground">{creator.qualityMetrics.overallScore} / 100</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Deadline Adherence</dt>
            <dd className="font-medium text-foreground">{creator.qualityMetrics.deadlineAdherence}%</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Revision Rate</dt>
            <dd className="font-medium text-foreground">{creator.qualityMetrics.revisionRate.toFixed(1)} / deliverable</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Dispute Rate</dt>
            <dd className="font-medium text-foreground">{creator.qualityMetrics.disputeRate}%</dd>
          </div>
        </dl>

        <div className="border-t border-border pt-4">
          <AlertDialog
            open={tierOpen}
            onOpenChange={(open) => {
              setTierOpen(open);
              if (open) {
                setNewTier(creator.tier);
                setTierReason('');
                setTierError(null);
              }
            }}
          >
            <AlertDialogTrigger asChild>
              <Button type="button" size="sm" variant="outline">
                <ArrowUpDown aria-hidden="true" />
                Adjust Tier
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Adjust tier for {creator.name}?</AlertDialogTitle>
                <AlertDialogDescription>
                  This overrides the creator's tier independent of the daily score calculation job.
                  Recorded on the creator's review trail. Provide a reason.
                </AlertDialogDescription>
              </AlertDialogHeader>

              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="creator-new-tier">New Tier</Label>
                  <Select value={newTier} onValueChange={(value) => setNewTier(value as Creator['tier'])}>
                    <SelectTrigger id="creator-new-tier" disabled={tierLoading}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="NANO">Nano</SelectItem>
                      <SelectItem value="MICRO">Micro</SelectItem>
                      <SelectItem value="MID">Mid</SelectItem>
                      <SelectItem value="MACRO">Macro</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <div className="flex items-center gap-2 rounded-lg border border-border bg-muted px-3 py-2 text-sm">
                  <span className="text-muted-foreground">{creator.tier}</span>
                  <ArrowRight className="size-3.5 text-muted-foreground" aria-hidden="true" />
                  <span className="font-medium text-foreground">{newTier}</span>
                </div>

                <Textarea
                  value={tierReason}
                  onChange={(e) => setTierReason(e.target.value)}
                  placeholder="Reason for tier adjustment (min. 10 characters, e.g. Manual override — follower audit confirmed higher reach than automated scoring)"
                  disabled={tierLoading}
                />
              </div>

              {tierError && (
                <p className="rounded-lg border border-destructive-foreground/30 bg-card px-3 py-2 text-xs text-destructive-foreground">
                  {tierError}
                </p>
              )}

              <AlertDialogFooter>
                <AlertDialogCancel disabled={tierLoading}>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  onClick={(e) => {
                    e.preventDefault();
                    void handleAdjustTier();
                  }}
                  disabled={tierLoading || tierReason.trim().length < 10}
                >
                  {tierLoading ? 'Saving…' : 'Confirm Tier Adjustment'}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </Card>

      {/* Collaboration history */}
      <Card className="gap-3 p-5">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Handshake className="size-4" aria-hidden="true" />
          Campaign Collaborations
        </h3>
        {creator.collaborationHistory.length === 0 ? (
          <p className="text-sm text-muted-foreground">No campaign collaborations on file.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-border">
            {creator.collaborationHistory.map((collab) => (
              <li key={collab.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                <div>
                  <p className="font-medium text-foreground">{collab.campaignName}</p>
                  <p className="text-muted-foreground">{collab.brandName}</p>
                </div>
                <div className="flex items-center gap-2">
                  <span className="font-medium text-foreground">{formatINR(collab.amount)}</span>
                  <Badge variant="outline">{collab.status}</Badge>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

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
          {creator.applicationStatus === CreatorApplicationStatus.PENDING && (
            <>
              <AlertDialog
                open={approveApplicationOpen}
                onOpenChange={(open) => {
                  setApproveApplicationOpen(open);
                  if (open) {
                    setApplicationApproveReason('');
                    setActionError(null);
                  }
                }}
              >
                <AlertDialogTrigger asChild>
                  <Button type="button" size="sm" disabled={actionLoading}>
                    <BadgeCheck aria-hidden="true" />
                    Approve Application
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>Approve application for {creator.name}?</AlertDialogTitle>
                    <AlertDialogDescription>
                      This is recorded against the creator's application review trail. Provide a justification for
                      the approval.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <Textarea
                    value={applicationApproveReason}
                    onChange={(e) => setApplicationApproveReason(e.target.value)}
                    placeholder="Justification for approval (e.g. meets tier + audience quality criteria)"
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
                        void handleApproveApplication();
                      }}
                      disabled={actionLoading || applicationApproveReason.trim().length === 0}
                    >
                      {actionLoading ? 'Approving…' : 'Approve Application'}
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>

              <AlertDialog
                open={rejectApplicationOpen}
                onOpenChange={(open) => {
                  setRejectApplicationOpen(open);
                  if (open) {
                    setApplicationRejectReason('');
                    setActionError(null);
                  }
                }}
              >
                <AlertDialogTrigger asChild>
                  <Button type="button" size="sm" variant="destructive" disabled={actionLoading}>
                    <ShieldX aria-hidden="true" />
                    Reject Application
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>Reject application for {creator.name}?</AlertDialogTitle>
                    <AlertDialogDescription>
                      This is recorded against the creator's application review trail. Provide a reason for the
                      rejection.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <Textarea
                    value={applicationRejectReason}
                    onChange={(e) => setApplicationRejectReason(e.target.value)}
                    placeholder="Reason for rejection (e.g. audience quality below tier threshold)"
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
                        void handleRejectApplication();
                      }}
                      disabled={actionLoading || applicationRejectReason.trim().length === 0}
                    >
                      {actionLoading ? 'Rejecting…' : 'Reject Application'}
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            </>
          )}

          {!creator.instagramVerified && (
            <Button type="button" size="sm" variant="outline" onClick={handleForceInstagramReauth} disabled={actionLoading}>
              <RefreshCw aria-hidden="true" />
              Force Instagram Re-auth
            </Button>
          )}

          {creator.isSuspended ? (
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
                  <AlertDialogTitle>Reinstate {creator.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    The creator will regain access to active collaborations. Provide a reason for the audit trail.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <Textarea
                  value={reinstateReason}
                  onChange={(e) => setReinstateReason(e.target.value)}
                  placeholder="Reason for reinstatement (e.g. appeal reviewed, standing restored)"
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
                  <AlertDialogTitle>Suspend {creator.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    The creator will lose access to active collaborations until reinstated. Provide a reason for the
                    audit trail.
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
            <a href={`mailto:${creator.email}`}>
              <Mail aria-hidden="true" />
              Contact Creator
            </a>
          </Button>

          {creator.flaggedContentCount > 0 && (
            <span className="ml-auto flex items-center gap-1.5 text-xs text-destructive-foreground">
              <Flag className="size-3.5" aria-hidden="true" />
              {creator.flaggedContentCount} flagged item{creator.flaggedContentCount === 1 ? '' : 's'} pending review
            </span>
          )}
        </div>
      </Card>
    </div>
  );
}
