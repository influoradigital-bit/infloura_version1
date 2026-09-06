/**
 * INFLUORA ADMIN PANEL — Festival Box Enquiries
 * Owner: Ananya (Frontend)
 * Reference: T-FESTIVALBOX-0905
 *
 * Admin inbox for /festival-box submissions — both BRAND (sponsor a slot) and CREATOR (join the
 * roster) enquiries land in the same table, pre-signup. Half the row is always null depending on
 * `type`: a BRAND row has no `instagramHandle`/`followers`/`city`; a CREATOR row has no
 * `company`/`website`/`tier`/`productCategory`. Absent fields render as an em dash — never an
 * empty cell or "0" — so "not asked" stays visibly distinct from "answered with nothing".
 *
 * Same table+Sheet-drawer shell as TicketList.tsx/CampaignTable.tsx, and the same manual-
 * URLSearchParams pagination as CreatorConnectionsPage. Backed by `festivalEnquiryApi`
 * (src/admin/services/api-contracts.ts) against the real `AdminFestivalEnquiryController`.
 *
 * Status pipeline is NEW → CONTACTED/QUALIFIED/WON/LOST. The backend refuses to move a row back
 * to NEW (400), so NEW is never offered as a target in the status control below — only shown as
 * the row's current state. `statusCounts` in the header totals across the WHOLE table, not the
 * current page/filter, and must not be recomputed client-side from `rows`.
 */

import { type ReactNode, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Building2,
  ExternalLink,
  Inbox,
  Instagram,
  Loader2,
  Mail,
  MapPin,
  Megaphone,
  Phone,
  Tag,
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
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import { useToast } from '@/hooks/use-toast';
import { FestivalCouponsPanel } from '../components/festival/FestivalCouponsPanel';
import { festivalEnquiryApi } from '../services/api-contracts';
import type {
  AdminFestivalEnquiry,
  FestivalEnquiryStatus,
  FestivalEnquiryType,
  FestivalSponsorProvisionResult,
} from '../services/api-contracts';

const PAGE_SIZE = 20;

/** Status a row can be MOVED to. NEW is deliberately excluded — the backend rejects it (400). */
const TARGET_STATUSES: Exclude<FestivalEnquiryStatus, 'NEW'>[] = [
  'CONTACTED',
  'QUALIFIED',
  'WON',
  'LOST',
];

const ALL_STATUSES: FestivalEnquiryStatus[] = ['NEW', 'CONTACTED', 'QUALIFIED', 'WON', 'LOST'];

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/** Never coerce a nullable count to 0 — "not asked" (BRAND row, followers) must read differently
 *  from "asked and answered with zero" (which this field never actually is, but the discipline
 *  is the same one CreatorConnectionsPage uses for `AdminConnection.followers`). */
function formatFollowers(n: number | null): string {
  if (n == null) return '—';
  if (n >= 10000000) return `${(n / 10000000).toFixed(1)}Cr`;
  if (n >= 100000) return `${(n / 100000).toFixed(1)}L`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return String(n);
}

function orDash(value: string | null | undefined): string {
  return value && value.trim().length > 0 ? value : '—';
}

function titleCaseStatus(status: string): string {
  return status[0] + status.slice(1).toLowerCase();
}

// ============================================
// STATUS / TYPE PILLS — solid AA-legible fill, same convention as CreatorConnectionsPage's
// StatusPill.
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

function statusTone(status: FestivalEnquiryStatus): PillTone {
  switch (status) {
    case 'NEW':
      return 'warning';
    case 'CONTACTED':
    case 'QUALIFIED':
      return 'neutral';
    case 'WON':
      return 'success';
    case 'LOST':
    default:
      return 'destructive';
  }
}

/**
 * Compact table-column indicator for T-FESTIVALBOX-0905 phase 2. A row is only ever a candidate
 * for provisioning once it is `WON` and `BRAND` — everything else (a CREATOR row, or a BRAND row
 * not yet Won) is "not applicable" and renders as `orDash`'s em dash, never a blank cell or "0".
 * `provisionedWorkspaceId` wins over the applicability check: once provisioned, always shown as
 * Provisioned even if status later moves on, since the workspace still exists.
 */
function SponsorIndicator({ row }: { row: AdminFestivalEnquiry }) {
  if (row.provisionedWorkspaceId) {
    return <StatusPill tone="success">Provisioned</StatusPill>;
  }
  if (row.type === 'BRAND' && row.status === 'WON') {
    return <StatusPill tone="warning">Pending</StatusPill>;
  }
  return <span className="text-muted-foreground">{orDash(null)}</span>;
}

// ============================================
// DETAIL DRAWER — full enquiry + status/notes editor. Keyed by enquiry id from the parent so
// its local draft state resets whenever a different row is opened.
// ============================================

function EnquiryDetailDrawer({
  enquiry,
  open,
  onOpenChange,
}: {
  enquiry: AdminFestivalEnquiry;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  // `enquiry.status === 'NEW'` has no valid "keep as-is" resend (the backend rejects NEW), so the
  // draft starts empty for a NEW row and the admin must explicitly pick a target before saving —
  // for any other row it defaults to the current status (a same-status save is allowed and is
  // how an admin edits notes alone).
  const [statusDraft, setStatusDraft] = useState<Exclude<FestivalEnquiryStatus, 'NEW'> | ''>(
    enquiry.status === 'NEW' ? '' : enquiry.status,
  );
  const [notesDraft, setNotesDraft] = useState('');
  const [notesTouched, setNotesTouched] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // T-FESTIVALBOX-0905 phase 2 — "Provision sponsor". `provisionResult` holds this call's own
  // response so the drawer can show what was created immediately. It does NOT get replaced by
  // the invalidated list refetch below: `AdminFestivalEnquiryDto` (the list/GET/PATCH shape)
  // has not yet grown the four `provisioned*` fields the way `FestivalSponsorProvisionResult`
  // has them — see that type's doc comment in api-contracts.ts. Verified by reading the real
  // backend DTOs directly, not assumed. Until the backend read-side ships, this is the only
  // source "Provisioned" can render from, and only for the lifetime of this open drawer.
  const [showProvisionConfirm, setShowProvisionConfirm] = useState(false);
  const [provisionError, setProvisionError] = useState<string | null>(null);
  const [provisionResult, setProvisionResult] = useState<FestivalSponsorProvisionResult | null>(null);
  // Belt-and-suspenders double-fire guard: a ref mutates synchronously, so even a second click
  // event dispatched before React re-renders `provisionMutation.isPending` is still caught —
  // relying on `isPending` alone leaves a window between the click and the re-render.
  const provisionInFlightRef = useRef(false);

  const provisionMutation = useMutation({
    mutationFn: () => festivalEnquiryApi.provision(enquiry.id),
    onSuccess: (res) => {
      if (!res.success) {
        setProvisionError(res.error ?? 'Provisioning failed. Please try again.');
        return;
      }
      setProvisionError(null);
      setProvisionResult(res.data ?? null);
      queryClient.invalidateQueries({ queryKey: ['admin', 'festival-enquiries'] });
      toast({
        title: 'Sponsor provisioned',
        description: `Workspace created and a password-set link was emailed to ${enquiry.email}.`,
      });
    },
    onError: (err: Error) => {
      setProvisionError(err.message || 'Provisioning failed. Please try again.');
    },
    onSettled: () => {
      provisionInFlightRef.current = false;
    },
  });

  const handleConfirmProvision = () => {
    if (provisionInFlightRef.current || provisionMutation.isPending) return;
    provisionInFlightRef.current = true;
    setShowProvisionConfirm(false);
    provisionMutation.mutate();
  };

  const updateMutation = useMutation({
    mutationFn: () =>
      festivalEnquiryApi.updateStatus(enquiry.id, {
        status: statusDraft as Exclude<FestivalEnquiryStatus, 'NEW'>,
        // Only include `notes` if the admin actually touched the field — omitting it leaves the
        // existing note intact server-side; including '' is how a note gets deliberately cleared.
        notes: notesTouched ? notesDraft : undefined,
      }),
    onSuccess: (res) => {
      if (!res.success) {
        setSaveError(res.error ?? 'Update failed. Please try again.');
        return;
      }
      queryClient.invalidateQueries({ queryKey: ['admin', 'festival-enquiries'] });
      toast({ title: 'Enquiry updated', description: `Status set to ${titleCaseStatus(statusDraft)}.` });
      setSaveError(null);
      setNotesTouched(false);
      onOpenChange(false);
    },
    onError: (err: Error) => {
      setSaveError(err.message || 'Update failed. Please try again.');
    },
  });

  const isBrand = enquiry.type === 'BRAND';
  const canSave = statusDraft !== '' && !updateMutation.isPending;

  // `provisionResult` (this call's own response) wins over `enquiry`'s `provisioned*` fields,
  // which today are always undefined from the list/GET/PATCH endpoints (see the state comment
  // above) — kept as the fallback anyway so this activates for free once the backend read-side
  // catches up, without needing another FE change.
  const provisionedWorkspaceId = provisionResult?.workspaceId ?? enquiry.provisionedWorkspaceId;
  const provisionedUserId = provisionResult?.userId ?? enquiry.provisionedUserId;
  const provisionedCampaignId = provisionResult?.campaignId ?? enquiry.provisionedCampaignId;
  const provisionedAt = provisionResult?.provisionedAt ?? enquiry.provisionedAt;
  const isProvisioned = Boolean(provisionedWorkspaceId);
  const canProvision = isBrand && enquiry.status === 'WON' && !isProvisioned;
  const provisionDisabledReason = isProvisioned
    ? null
    : !isBrand
      ? 'Creator enquiries are never provisioned this way.'
      : enquiry.status !== 'WON'
        ? 'Only a Won enquiry can be provisioned — move it to Won first.'
        : null;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>{enquiry.name}</SheetTitle>
          <SheetDescription>
            {isBrand ? 'Brand' : 'Creator'} enquiry · {enquiry.edition}
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 px-4 pb-4">
          <div className="flex flex-wrap items-center gap-2">
            <StatusPill tone={statusTone(enquiry.status)}>{titleCaseStatus(enquiry.status)}</StatusPill>
            <Badge variant="outline">{isBrand ? 'Brand' : 'Creator'}</Badge>
          </div>

          {/* Contact */}
          <dl className="grid grid-cols-[1.25rem_1fr] items-center gap-x-2 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
            <Mail className="size-4 text-muted-foreground" aria-hidden="true" />
            <dd className="text-foreground">{enquiry.email}</dd>
            <Phone className="size-4 text-muted-foreground" aria-hidden="true" />
            <dd className="text-foreground">{enquiry.phone}</dd>
          </dl>

          {/* Type-specific fields */}
          {isBrand ? (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <Building2 className="size-3.5" aria-hidden="true" /> Company
              </dt>
              <dd className="text-foreground">{orDash(enquiry.company)}</dd>
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <ExternalLink className="size-3.5" aria-hidden="true" /> Website
              </dt>
              <dd className="text-foreground">
                {enquiry.website ? (
                  <a
                    href={enquiry.website}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-primary underline underline-offset-2"
                  >
                    {enquiry.website}
                  </a>
                ) : (
                  '—'
                )}
              </dd>
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <Tag className="size-3.5" aria-hidden="true" /> Tier
              </dt>
              <dd className="text-foreground">{orDash(enquiry.tier)}</dd>
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <Megaphone className="size-3.5" aria-hidden="true" /> Product category
              </dt>
              <dd className="text-foreground">{orDash(enquiry.productCategory)}</dd>
            </dl>
          ) : (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <Instagram className="size-3.5" aria-hidden="true" /> Instagram
              </dt>
              <dd className="text-foreground">
                {enquiry.instagramHandle ? `@${enquiry.instagramHandle}` : '—'}
              </dd>
              <dt className="text-muted-foreground">Followers</dt>
              <dd className="text-foreground">{formatFollowers(enquiry.followers)}</dd>
              <dt className="flex items-center gap-1.5 text-muted-foreground">
                <MapPin className="size-3.5" aria-hidden="true" /> City
              </dt>
              <dd className="text-foreground">{orDash(enquiry.city)}</dd>
            </dl>
          )}

          {/* Message */}
          <div className="flex flex-col gap-1.5">
            <h3 className="text-sm font-semibold text-foreground">Message</h3>
            <p className="whitespace-pre-wrap rounded-lg border border-border bg-card p-3 text-sm text-foreground">
              {orDash(enquiry.message)}
            </p>
          </div>

          {/* UTM provenance */}
          <div className="flex flex-col gap-1.5">
            <h3 className="text-sm font-semibold text-foreground">Source</h3>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
              <dt className="text-muted-foreground">UTM source</dt>
              <dd className="text-foreground">{orDash(enquiry.utmSource)}</dd>
              <dt className="text-muted-foreground">UTM medium</dt>
              <dd className="text-foreground">{orDash(enquiry.utmMedium)}</dd>
              <dt className="text-muted-foreground">UTM campaign</dt>
              <dd className="text-foreground">{orDash(enquiry.utmCampaign)}</dd>
              <dt className="text-muted-foreground">Submitted</dt>
              <dd className="text-foreground">{formatDateTime(enquiry.createdAt)}</dd>
            </dl>
          </div>

          {/* Existing note (read-only) + handling metadata */}
          <div className="flex flex-col gap-1.5">
            <h3 className="text-sm font-semibold text-foreground">Current note</h3>
            <p className="rounded-lg border border-border bg-muted px-3 py-2 text-sm text-muted-foreground">
              {orDash(enquiry.adminNotes)}
            </p>
            {(enquiry.handledBy || enquiry.handledAt) && (
              <p className="text-xs text-muted-foreground">
                Last handled by {orDash(enquiry.handledBy)} · {formatDateTime(enquiry.handledAt)}
              </p>
            )}
          </div>

          {/* Sponsor account — T-FESTIVALBOX-0905 phase 2 */}
          <div className="flex flex-col gap-2 rounded-lg border border-border bg-card p-3">
            <h3 className="text-sm font-semibold text-foreground">Sponsor account</h3>

            {isProvisioned ? (
              <dl className="grid grid-cols-[6rem_1fr] gap-x-2 gap-y-1.5 text-sm">
                <dt className="text-muted-foreground">Workspace</dt>
                <dd className="truncate font-mono text-xs text-foreground">{orDash(provisionedWorkspaceId)}</dd>
                <dt className="text-muted-foreground">Campaign</dt>
                <dd className="truncate font-mono text-xs text-foreground">{orDash(provisionedCampaignId)}</dd>
                <dt className="text-muted-foreground">User</dt>
                <dd className="truncate font-mono text-xs text-foreground">{orDash(provisionedUserId)}</dd>
                <dt className="text-muted-foreground">Provisioned</dt>
                <dd className="text-foreground">{formatDateTime(provisionedAt)}</dd>
              </dl>
            ) : null}

            {/* Coupon codes — T-FESTIVALBOX-0905 phase 10, screen 1. Reachable once this sponsor
                is provisioned (a campaign id exists to register coupons against); see
                FestivalCouponsPanel.tsx for why every control in it is disabled+captioned. */}
            {isProvisioned && provisionedCampaignId && (
              <FestivalCouponsPanel
                campaignId={provisionedCampaignId}
                sponsorName={enquiry.company || enquiry.name}
              />
            )}

            {!isProvisioned && (
              <>
                <p className="text-xs text-muted-foreground">
                  Creates a real Influora account and workspace for this brand, and emails them a
                  password-set link.
                </p>
                <Button
                  type="button"
                  variant="outline"
                  disabled={!canProvision || provisionMutation.isPending}
                  onClick={() => setShowProvisionConfirm(true)}
                  className="w-fit"
                >
                  {provisionMutation.isPending ? (
                    <Spinner className="mr-1.5" aria-hidden="true" />
                  ) : null}
                  Provision sponsor
                </Button>
                {provisionDisabledReason && (
                  <p className="text-xs text-muted-foreground">{provisionDisabledReason}</p>
                )}
              </>
            )}

            {provisionResult && !provisionError && (
              <p className="text-xs text-success-foreground">
                Sponsor provisioned — workspace {provisionResult.workspaceId}, campaign{' '}
                {provisionResult.campaignId}, and a password-set link was emailed to{' '}
                {enquiry.email}.
              </p>
            )}

            {provisionError && (
              <p role="alert" className="text-sm text-destructive-foreground">
                {provisionError}
              </p>
            )}
          </div>

          <AlertDialog
            open={showProvisionConfirm}
            onOpenChange={(nextOpen) => {
              // Ignore attempts to close (Escape/overlay click) once the request is in flight —
              // the confirm step must not be dismissible mid-request.
              if (provisionMutation.isPending) return;
              setShowProvisionConfirm(nextOpen);
            }}
          >
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Provision sponsor account?</AlertDialogTitle>
                <AlertDialogDescription>
                  This creates a real Influora account and workspace for{' '}
                  <strong>{orDash(enquiry.company) !== '—' ? enquiry.company : enquiry.name}</strong>{' '}
                  and emails a password-set link to <strong>{enquiry.email}</strong>. This cannot
                  be undone from here.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel disabled={provisionMutation.isPending}>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  disabled={provisionMutation.isPending}
                  onClick={(e) => {
                    // AlertDialogAction closes on click by default — handleConfirmProvision
                    // already closes it explicitly and (critically) sets the in-flight ref
                    // synchronously before mutate(), so a second click racing this one is a
                    // no-op regardless of render timing.
                    e.preventDefault();
                    handleConfirmProvision();
                  }}
                >
                  {provisionMutation.isPending ? (
                    <Spinner className="mr-1.5" aria-hidden="true" />
                  ) : null}
                  Confirm & provision
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>

          {/* Status + notes editor */}
          <div className="mt-auto flex flex-col gap-3 border-t border-border pt-4">
            <h3 className="text-sm font-semibold text-foreground">Update</h3>

            {saveError && (
              <p role="alert" className="text-sm text-destructive-foreground">
                {saveError}
              </p>
            )}

            <div className="flex flex-col gap-1.5">
              <label htmlFor="fe-status" className="text-sm font-medium text-foreground">
                New status
              </label>
              <Select
                value={statusDraft}
                onValueChange={(value) => setStatusDraft(value as Exclude<FestivalEnquiryStatus, 'NEW'>)}
              >
                <SelectTrigger id="fe-status" aria-label="New status">
                  <SelectValue placeholder="Select a status…" />
                </SelectTrigger>
                <SelectContent>
                  {TARGET_STATUSES.map((s) => (
                    <SelectItem key={s} value={s}>
                      {titleCaseStatus(s)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {enquiry.status === 'NEW' && (
                <p className="text-xs text-muted-foreground">
                  This enquiry has not been touched yet — an enquiry can never be moved back to
                  New, so pick where it goes next.
                </p>
              )}
            </div>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="fe-notes" className="text-sm font-medium text-foreground">
                Notes (leave blank to keep the current note)
              </label>
              <Textarea
                id="fe-notes"
                value={notesDraft}
                onChange={(e) => {
                  setNotesDraft(e.target.value.slice(0, 2000));
                  setNotesTouched(true);
                }}
                maxLength={2000}
                placeholder="Internal note for the audit trail…"
                rows={3}
              />
              {notesTouched && notesDraft.trim().length === 0 && (
                <p className="text-xs text-muted-foreground">
                  Saving will clear the existing note.
                </p>
              )}
            </div>

            <div className="flex justify-end gap-2">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button type="button" disabled={!canSave} onClick={() => updateMutation.mutate()}>
                {updateMutation.isPending ? (
                  <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden="true" />
                ) : null}
                Save
              </Button>
            </div>
          </div>
        </div>
      </SheetContent>
    </Sheet>
  );
}

// ============================================
// MAIN PAGE
// ============================================

export default function FestivalEnquiriesPage() {
  const [type, setType] = useState<FestivalEnquiryType | 'ALL'>('ALL');
  const [status, setStatus] = useState<FestivalEnquiryStatus | 'ALL'>('ALL');
  const [edition, setEdition] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: ['admin', 'festival-enquiries', type, status, edition, q, page],
    queryFn: () =>
      festivalEnquiryApi.list({
        type: type === 'ALL' ? undefined : type,
        status: status === 'ALL' ? undefined : status,
        edition: edition || undefined,
        q: q || undefined,
        page,
        pageSize: PAGE_SIZE,
      }),
  });

  const rows = listQuery.data?.success ? listQuery.data.data?.items ?? [] : [];
  const total = listQuery.data?.success ? listQuery.data.data?.total ?? 0 : 0;
  const hasMore = listQuery.data?.success ? listQuery.data.data?.hasMore ?? false : false;
  // Seeded with every status at 0 server-side, so this is never an empty object even before the
  // first fetch resolves — but guard anyway rather than assume the network already succeeded.
  const statusCounts = listQuery.data?.success ? listQuery.data.data?.statusCounts ?? {} : {};
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const loadError = !listQuery.isLoading && listQuery.data && !listQuery.data.success ? listQuery.data.error : null;

  const selected = rows.find((r) => r.id === selectedId) ?? null;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <Inbox className="size-6" aria-hidden="true" />
          Festival Box enquiries
        </h2>
        <p className="text-sm text-muted-foreground">
          Brand sponsorship and creator roster enquiries submitted on /festival-box, across every
          edition.
        </p>
      </div>

      {/* Status counts — totals across the WHOLE table; never recomputed from the current page. */}
      <div className="flex flex-wrap gap-2">
        {ALL_STATUSES.map((s) => (
          <div
            key={s}
            className="flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2 text-sm"
          >
            <StatusPill tone={statusTone(s)}>{titleCaseStatus(s)}</StatusPill>
            <span className="font-semibold text-foreground">{statusCounts[s] ?? 0}</span>
          </div>
        ))}
      </div>

      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
          <Input
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setPage(1);
            }}
            placeholder="Search name, email, company, handle…"
            className="sm:max-w-xs"
            aria-label="Search enquiries"
          />
          <Input
            value={edition}
            onChange={(e) => {
              setEdition(e.target.value);
              setPage(1);
            }}
            placeholder="Edition (e.g. EDITION_02)"
            className="sm:max-w-48"
            aria-label="Filter by edition"
          />
          <Select
            value={type}
            onValueChange={(value) => {
              setType(value as FestivalEnquiryType | 'ALL');
              setPage(1);
            }}
          >
            <SelectTrigger className="sm:w-36" aria-label="Filter by type">
              <SelectValue placeholder="All types" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All types</SelectItem>
              <SelectItem value="BRAND">Brand</SelectItem>
              <SelectItem value="CREATOR">Creator</SelectItem>
            </SelectContent>
          </Select>
          <Select
            value={status}
            onValueChange={(value) => {
              setStatus(value as FestivalEnquiryStatus | 'ALL');
              setPage(1);
            }}
          >
            <SelectTrigger className="sm:w-40" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              {ALL_STATUSES.map((s) => (
                <SelectItem key={s} value={s}>
                  {titleCaseStatus(s)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <span className="whitespace-nowrap text-sm text-muted-foreground">
          {listQuery.isLoading ? 'Loading…' : `${rows.length} of ${total} enquiries`}
        </span>
      </div>

      {loadError && (
        <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load Festival Box enquiries: {loadError}
        </div>
      )}

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Type</TableHead>
              <TableHead>Name</TableHead>
              <TableHead>Edition</TableHead>
              <TableHead>Details</TableHead>
              <TableHead>Status</TableHead>
              {/* T-FESTIVALBOX-0905 phase 2 — lets an admin scan for still-unprovisioned Won
                  brand rows without opening each one. */}
              <TableHead>Sponsor</TableHead>
              <TableHead>Submitted</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {listQuery.isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 8 }).map((__, j) => (
                    <TableCell key={j}>
                      <Skeleton className="h-4 w-full max-w-28" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Inbox className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No Festival Box enquiries match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((row) => (
                <TableRow key={row.id}>
                  <TableCell>
                    <Badge variant="outline">{row.type === 'BRAND' ? 'Brand' : 'Creator'}</Badge>
                  </TableCell>
                  <TableCell>
                    <div className="min-w-0">
                      <p className="truncate font-medium text-foreground">{row.name}</p>
                      <p className="truncate text-xs text-muted-foreground">{row.email}</p>
                    </div>
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">{row.edition}</TableCell>
                  <TableCell className="text-muted-foreground">
                    {row.type === 'BRAND' ? (
                      <span className="truncate">{orDash(row.company)}</span>
                    ) : (
                      <span className="truncate">
                        {row.instagramHandle ? `@${row.instagramHandle}` : '—'} ·{' '}
                        {formatFollowers(row.followers)}
                      </span>
                    )}
                  </TableCell>
                  <TableCell>
                    <StatusPill tone={statusTone(row.status)}>{titleCaseStatus(row.status)}</StatusPill>
                  </TableCell>
                  <TableCell>
                    <SponsorIndicator row={row} />
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {formatDateTime(row.createdAt)}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button type="button" size="sm" variant="outline" onClick={() => setSelectedId(row.id)}>
                      View
                    </Button>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      {(totalPages > 1 || hasMore) && (
        <div className="flex items-center justify-between border-t border-border pt-3">
          <Button type="button" variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
            Previous
          </Button>
          <span className="text-sm text-muted-foreground">
            Page {page} of {totalPages}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!hasMore && page >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      )}

      {selected && (
        <EnquiryDetailDrawer
          key={selected.id}
          enquiry={selected}
          open={Boolean(selected)}
          onOpenChange={(open) => {
            if (!open) setSelectedId(null);
          }}
        />
      )}
    </div>
  );
}
