/**
 * INFLUORA ADMIN PANEL — Content Moderation Flag Queue
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P2 — "Content flag queue")
 *
 * Admin moderation queue for `ContentFlag` rows (deliverables/profiles/
 * messages flagged by the AI pipeline, a user report, or an admin): table of
 * content type/id, flag reason, flagged by, flagged at, status — filterable
 * by status — with a row-level detail Sheet carrying the actions area
 * (approve & remove content / dismiss flag / escalate), each gated behind a
 * mandatory-reason confirmation. Same "reason-required pattern" as
 * `BrandProfile.tsx` / `CreatorProfile.tsx`'s suspend/reject actions, and
 * the same table+Sheet-drawer shell as `TicketList.tsx`.
 *
 * Data comes from `useFlagQueue()` (src/admin/hooks/useFlagQueue.ts),
 * live-wired via a `react-query` `useQuery` to `moderationApi.getContentFlags()`,
 * backed by the real `AdminModerationController` (Vikram, 2026-07-12). The
 * actions area below is a `react-query` `useMutation` against the real
 * `moderationApi.actionFlag()` endpoint (`POST /moderation/flags/:id/action`),
 * with `queryClient.invalidateQueries` on success — not a stub.
 */

import { type ReactNode, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  Ban,
  Bot,
  CheckCircle2,
  Clock,
  Eye,
  Flag,
  FileWarning,
  MessageSquareWarning,
  ShieldAlert,
  TriangleAlert,
  User,
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
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import { useFlagQueue, type FlagSortField } from '../../hooks/useFlagQueue';
import { ContentFlagStatus } from '../../types/admin.types';
import type { ContentFlag } from '../../types/admin.types';
import { moderationApi } from '../../services/api-contracts';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

// ============================================
// STATUS / SOURCE PILLS — solid, AA-legible fill (not a washed-out pastel
// badge), same pattern as TicketList/CampaignTable StatusPill per prior
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

function statusPillTone(status: ContentFlagStatus): PillTone {
  switch (status) {
    case ContentFlagStatus.PENDING:
      return 'destructive';
    case ContentFlagStatus.REVIEWED:
      return 'warning';
    case ContentFlagStatus.ACTIONED:
    default:
      return 'success';
  }
}

function statusLabel(status: ContentFlagStatus): string {
  return status
    .toLowerCase()
    .split('_')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

function statusIcon(status: ContentFlagStatus) {
  switch (status) {
    case ContentFlagStatus.PENDING:
      return Clock;
    case ContentFlagStatus.REVIEWED:
      return Eye;
    case ContentFlagStatus.ACTIONED:
    default:
      return CheckCircle2;
  }
}

function contentTypeIcon(contentType: ContentFlag['contentType']) {
  switch (contentType) {
    case 'DELIVERABLE':
      return FileWarning;
    case 'PROFILE':
      return UserRound;
    case 'MESSAGE':
    default:
      return MessageSquareWarning;
  }
}

function flaggedByIcon(flaggedBy: ContentFlag['flaggedBy']) {
  switch (flaggedBy) {
    case 'AI':
      return Bot;
    case 'ADMIN':
      return ShieldAlert;
    case 'USER':
    default:
      return User;
  }
}

// ============================================
// SORTABLE HEADER
// ============================================

interface SortableHeadProps {
  field: FlagSortField;
  activeField: FlagSortField;
  direction: 'asc' | 'desc';
  onSort: (field: FlagSortField) => void;
  children: ReactNode;
  className?: string;
}

function SortableHead({ field, activeField, direction, onSort, children, className }: SortableHeadProps) {
  const isActive = field === activeField;
  const Icon = isActive ? (direction === 'asc' ? ArrowUp : ArrowDown) : ArrowUpDown;

  return (
    <TableHead className={className}>
      <button
        type="button"
        onClick={() => onSort(field)}
        className={cn(
          'inline-flex items-center gap-1 rounded text-left font-medium text-foreground hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
          isActive && 'text-primary',
        )}
      >
        {children}
        <Icon className="size-3.5 shrink-0" aria-hidden="true" />
      </button>
    </TableHead>
  );
}

// ============================================
// MODERATION ACTION — reason-required pattern (same as BrandProfile /
// CreatorProfile's suspend/reject AlertDialog + Textarea).
// ============================================

type FlagModerationAction = 'REMOVE' | 'REJECT' | 'ESCALATE';

interface ModerationActionButtonProps {
  action: FlagModerationAction;
  label: string;
  icon: typeof Ban;
  variant: 'default' | 'destructive' | 'outline';
  title: string;
  description: string;
  placeholder: string;
  onConfirm: (reason: string) => void;
}

function ModerationActionButton({
  action,
  label,
  icon: Icon,
  variant,
  title,
  description,
  placeholder,
  onConfirm,
}: ModerationActionButtonProps) {
  const [reason, setReason] = useState('');

  return (
    <AlertDialog
      onOpenChange={(open) => {
        if (!open) setReason('');
      }}
    >
      <AlertDialogTrigger asChild>
        <Button type="button" size="sm" variant={variant} data-action={action}>
          <Icon aria-hidden="true" />
          {label}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <Textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder={placeholder} />
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction disabled={!reason.trim()} onClick={() => onConfirm(reason.trim())}>
            {label}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

// ============================================
// DETAIL DRAWER — flag detail + actions area
// ============================================

function FlagDetailDrawer({ flag, onOpenChange }: { flag: ContentFlag | null; onOpenChange: (open: boolean) => void }) {
  const [actionNote, setActionNote] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const actionMutation = useMutation({
    mutationFn: ({ flagId, actionPayload }: { flagId: string; actionPayload: { action: 'REMOVE' | 'REJECT' | 'WARN' | 'ESCALATE'; reason: string } }) =>
      moderationApi.actionFlag(flagId, {
        entityId: flagId,
        entityType: 'CONTENT_FLAG',
        action: actionPayload.action as 'REMOVE' | 'REJECT' | 'WARN' | 'ESCALATE' | 'APPROVE',
        reason: actionPayload.reason,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'content-flags'] });
      setActionNote('Action applied successfully.');
      setTimeout(() => onOpenChange(false), 1500);
    },
    onError: (error: any) => {
      setActionNote(`Action failed: ${error?.message || 'Unknown error'}`);
    },
  });

  function handleAction(action: FlagModerationAction, reason: string) {
    if (!flag) return;
    // Map UI action to API action (backend expects 'REMOVE', 'REJECT', 'WARN', 'ESCALATE')
    // UI currently only has REMOVE, REJECT, ESCALATE (no WARN button yet)
    const apiAction: 'REMOVE' | 'REJECT' | 'WARN' | 'ESCALATE' =
      action === 'REMOVE' ? 'REMOVE' :
      action === 'REJECT' ? 'REJECT' : 'ESCALATE';
    actionMutation.mutate({ flagId: flag.id, actionPayload: { action: apiAction, reason } });
  }

  const ContentIcon = flag ? contentTypeIcon(flag.contentType) : Flag;
  const FlaggedByIcon = flag ? flaggedByIcon(flag.flaggedBy) : User;
  const isOpenForAction = flag ? flag.status !== ContentFlagStatus.ACTIONED : false;

  return (
    <Sheet
      open={Boolean(flag)}
      onOpenChange={(open) => {
        if (!open) setActionNote(null);
        onOpenChange(open);
      }}
    >
      <SheetContent side="right" className="w-full sm:max-w-lg">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <ContentIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            {flag ? `${flag.contentType} · ${flag.contentId}` : 'Flag'}
          </SheetTitle>
          <SheetDescription>{flag?.flagReason ?? 'Content moderation flag detail'}</SheetDescription>
        </SheetHeader>

        {flag && (
          <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4">
            <div className="flex flex-wrap items-center gap-2">
              <StatusPill tone={statusPillTone(flag.status)}>{statusLabel(flag.status)}</StatusPill>
              <Badge variant="outline" className="inline-flex items-center gap-1">
                <FlaggedByIcon className="size-3.5" aria-hidden="true" />
                Flagged by {flag.flaggedBy}
              </Badge>
            </div>

            {flag.contentPreview && (
              <div className="rounded-lg border border-border bg-card p-3 text-sm text-foreground">
                {flag.contentPreview}
              </div>
            )}

            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
              <dt className="text-muted-foreground">Flagged at</dt>
              <dd className="text-foreground">{formatDateTime(flag.createdAt)}</dd>
              {flag.reviewedAt && (
                <>
                  <dt className="text-muted-foreground">Reviewed at</dt>
                  <dd className="text-foreground">{formatDateTime(flag.reviewedAt)}</dd>
                </>
              )}
              {flag.reviewedBy && (
                <>
                  <dt className="text-muted-foreground">Reviewed by</dt>
                  <dd className="text-foreground">{flag.reviewedBy}</dd>
                </>
              )}
              {flag.actionTaken && (
                <>
                  <dt className="text-muted-foreground">Action taken</dt>
                  <dd className="text-foreground">{flag.actionTaken}</dd>
                </>
              )}
            </dl>

            <div className="mt-auto flex flex-col gap-3 border-t border-border pt-4">
              <h3 className="text-sm font-semibold text-foreground">Actions</h3>

              {actionNote && (
                <p className="rounded-lg border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
                  {actionNote}
                </p>
              )}

              {isOpenForAction ? (
                <div className="flex flex-wrap items-center gap-2">
                  <ModerationActionButton
                    action="REMOVE"
                    label="Approve & Remove Content"
                    icon={Ban}
                    variant="destructive"
                    title="Remove this content?"
                    description="This confirms the flag and removes the underlying content. Provide a reason for the audit trail."
                    placeholder="Reason for removal (e.g. confirmed brand-safety violation)"
                    onConfirm={(reason) => handleAction('REMOVE', reason)}
                  />
                  <ModerationActionButton
                    action="REJECT"
                    label="Dismiss Flag"
                    icon={CheckCircle2}
                    variant="outline"
                    title="Dismiss this flag?"
                    description="This marks the flag as reviewed with no action against the content. Provide a reason for the audit trail."
                    placeholder="Reason for dismissal (e.g. false positive, content is compliant)"
                    onConfirm={(reason) => handleAction('REJECT', reason)}
                  />
                  <ModerationActionButton
                    action="ESCALATE"
                    label="Escalate"
                    icon={TriangleAlert}
                    variant="default"
                    title="Escalate this flag?"
                    description="This routes the flag to a senior admin for further review. Provide a reason for the audit trail."
                    placeholder="Reason for escalation (e.g. possible legal/compliance risk)"
                    onConfirm={(reason) => handleAction('ESCALATE', reason)}
                  />
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  This flag has already been actioned — no further action is available.
                </p>
              )}
            </div>
          </div>
        )}
      </SheetContent>
    </Sheet>
  );
}

// ============================================
// PROPS
// ============================================

export interface FlagQueueProps {
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function FlagQueue({ className }: FlagQueueProps) {
  const { flags, totalCount, pendingCount, isLoading, error, filters, setFilters, sort, setSort } = useFlagQueue();
  const [selectedFlag, setSelectedFlag] = useState<ContentFlag | null>(null);

  function handleSort(field: FlagSortField) {
    setSort({
      field,
      direction: sort.field === field && sort.direction === 'asc' ? 'desc' : 'asc',
    });
  }

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load content flags: {error}
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-4', className)}>
      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={filters.search ?? ''}
            onChange={(e) => setFilters({ ...filters, search: e.target.value || undefined })}
            placeholder="Search flag reason or content id…"
            className="sm:max-w-xs"
            aria-label="Search flags"
          />

          <Select
            value={filters.status ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, status: value === 'ALL' ? undefined : (value as ContentFlagStatus) })
            }
          >
            <SelectTrigger className="sm:w-40" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value={ContentFlagStatus.PENDING}>Pending</SelectItem>
              <SelectItem value={ContentFlagStatus.REVIEWED}>Reviewed</SelectItem>
              <SelectItem value={ContentFlagStatus.ACTIONED}>Actioned</SelectItem>
            </SelectContent>
          </Select>

          <Select
            value={filters.contentType ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({
                ...filters,
                contentType: value === 'ALL' ? undefined : (value as ContentFlag['contentType']),
              })
            }
          >
            <SelectTrigger className="sm:w-36" aria-label="Filter by content type">
              <SelectValue placeholder="All content" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All content</SelectItem>
              <SelectItem value="DELIVERABLE">Deliverable</SelectItem>
              <SelectItem value="PROFILE">Profile</SelectItem>
              <SelectItem value="MESSAGE">Message</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-3 whitespace-nowrap text-sm text-muted-foreground">
          {pendingCount > 0 && (
            <span className="inline-flex items-center gap-1.5 text-destructive-foreground">
              <Flag className="size-3.5" aria-hidden="true" />
              {pendingCount} pending
            </span>
          )}
          <span>{isLoading ? 'Loading…' : `${flags.length} of ${totalCount} flags`}</span>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <SortableHead
                field="contentType"
                activeField={sort.field}
                direction={sort.direction}
                onSort={handleSort}
              >
                Content
              </SortableHead>
              <TableHead>Flag Reason</TableHead>
              <SortableHead field="flaggedBy" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Flagged By
              </SortableHead>
              <SortableHead field="createdAt" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Flagged At
              </SortableHead>
              <SortableHead field="status" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Status
              </SortableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 5 }).map((__, j) => (
                    <TableCell key={j}>
                      <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : flags.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Flag className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No content flags match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              flags.map((flag) => {
                const ContentIcon = contentTypeIcon(flag.contentType);
                const FlaggedByIcon = flaggedByIcon(flag.flaggedBy);
                const StatusIcon = statusIcon(flag.status);
                return (
                  <TableRow
                    key={flag.id}
                    onClick={() => setSelectedFlag(flag)}
                    className="cursor-pointer"
                    tabIndex={0}
                    role="button"
                    aria-label={`Review flag: ${flag.flagReason}`}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        setSelectedFlag(flag);
                      }
                    }}
                  >
                    <TableCell className="font-medium text-foreground">
                      <span className="inline-flex items-center gap-1.5">
                        <ContentIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                        {flag.contentType}
                      </span>
                      <Badge variant="outline" className="ml-2 align-middle">
                        {flag.contentId}
                      </Badge>
                    </TableCell>
                    <TableCell className="max-w-80 whitespace-normal text-muted-foreground">
                      {flag.flagReason}
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      <span className="inline-flex items-center gap-1.5">
                        <FlaggedByIcon className="size-3.5" aria-hidden="true" />
                        {flag.flaggedBy}
                      </span>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatDateTime(flag.createdAt)}
                    </TableCell>
                    <TableCell>
                      <StatusPill tone={statusPillTone(flag.status)}>
                        <StatusIcon className="size-3.5" aria-hidden="true" />
                        {statusLabel(flag.status)}
                      </StatusPill>
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </div>

      <FlagDetailDrawer
        flag={selectedFlag}
        onOpenChange={(open) => {
          if (!open) setSelectedFlag(null);
        }}
      />
    </div>
  );
}
