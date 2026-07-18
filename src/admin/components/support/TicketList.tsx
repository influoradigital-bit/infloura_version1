/**
 * INFLUORA ADMIN PANEL — Support Ticket List
 * Owner: Ananya (Frontend)
 * Reference: src/admin/TASK_ASSIGNMENTS.md (P2 — Support ticket list)
 *
 * Admin support queue: subject, requester, status, priority, assigned admin,
 * last-updated, with a status filter and a search box. Row click opens a
 * detail drawer (Sheet) showing the message thread — client-side sort/filter
 * against live data, same pattern as `CampaignTable`/`BrandProfile`. The
 * drawer also carries the ticket actions area (reply / assign / escalate),
 * live-wired via react-query mutations against `supportApi`, same pattern as
 * `FlagQueue.tsx`'s moderation actions.
 *
 * Data comes from `useTicketList()` / `useTicketDetail()`
 * (src/admin/hooks/useTicketList.ts) — both live-wired to
 * `AdminSupportController` via `supportApi` in `services/api-contracts.ts`.
 */

import { type ReactNode, useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  Inbox,
  LifeBuoy,
  Loader2,
  MessageCircle,
  Send,
  TriangleAlert,
  User,
  UserPlus,
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
import {
  useTicketList,
  useTicketDetail,
  ticketDetailQueryKey,
  type TicketSortField,
} from '../../hooks/useTicketList';
import { useSupportStats } from '../../hooks/useSupportStats';
import { TicketStatus, TicketPriority } from '../../types/admin.types';
import type { SupportTicket } from '../../types/admin.types';
import { supportApi } from '../../services/api-contracts';
import KpiCard from '../dashboard/KpiCard';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/**
 * Formats a minutes duration for the stats strip. `0` is rendered as "No
 * data yet" rather than a literal "0m" — the backend has no distinct
 * sentinel for "nothing resolved yet" vs. "resolved instantly", and the
 * former is overwhelmingly the honest read for a fresh/quiet queue (see
 * useSupportStats.ts doc comment).
 */
function formatDuration(minutes: number): string {
  if (minutes <= 0) return 'No data yet';
  if (minutes < 60) return `${Math.round(minutes)}m`;
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes % 60);
  return rest > 0 ? `${hours}h ${rest}m` : `${hours}h`;
}

// ============================================
// STATUS / PRIORITY PILLS — solid, AA-legible fill (not a washed-out pastel
// badge), same pattern as CampaignTable/BrandProfile StatusPill per prior
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

function statusPillTone(status: TicketStatus): PillTone {
  switch (status) {
    case TicketStatus.OPEN:
      return 'destructive';
    case TicketStatus.IN_PROGRESS:
      return 'warning';
    case TicketStatus.WAITING_USER:
      return 'neutral';
    case TicketStatus.RESOLVED:
    case TicketStatus.CLOSED:
    default:
      return 'success';
  }
}

function priorityPillTone(priority: TicketPriority): PillTone {
  switch (priority) {
    case TicketPriority.URGENT:
      return 'destructive';
    case TicketPriority.HIGH:
      return 'warning';
    case TicketPriority.MEDIUM:
      return 'neutral';
    case TicketPriority.LOW:
    default:
      return 'success';
  }
}

function statusLabel(status: TicketStatus): string {
  return status
    .toLowerCase()
    .split('_')
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

// ============================================
// SORTABLE HEADER
// ============================================

interface SortableHeadProps {
  field: TicketSortField;
  activeField: TicketSortField;
  direction: 'asc' | 'desc';
  onSort: (field: TicketSortField) => void;
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
// DETAIL DRAWER
// ============================================

function TicketDetailDrawer({
  ticketId,
  onOpenChange,
  onActionSuccess,
}: {
  ticketId: string | null;
  onOpenChange: (open: boolean) => void;
  onActionSuccess: () => void;
}) {
  const { data: ticket, isLoading, error } = useTicketDetail(ticketId ?? undefined);
  const queryClient = useQueryClient();
  const [replyContent, setReplyContent] = useState('');
  const [assigneeId, setAssigneeId] = useState('');
  const [actionNote, setActionNote] = useState<string | null>(null);

  // Reset the composer/assign field and any stale status note whenever the
  // drawer switches to a different ticket (or closes).
  useEffect(() => {
    setReplyContent('');
    setAssigneeId('');
    setActionNote(null);
  }, [ticketId]);

  function afterActionSuccess() {
    if (ticketId) {
      void queryClient.invalidateQueries({ queryKey: ticketDetailQueryKey(ticketId) });
    }
    onActionSuccess();
  }

  const replyMutation = useMutation({
    mutationFn: (content: string) => {
      if (!ticketId) throw new Error('No ticket selected.');
      return supportApi.reply(ticketId, content);
    },
    onSuccess: (res) => {
      if (!res.success) {
        setActionNote(`Reply failed: ${res.error ?? 'Unknown error'}`);
        return;
      }
      setReplyContent('');
      setActionNote('Reply sent.');
      afterActionSuccess();
    },
    onError: (err: unknown) => {
      setActionNote(`Reply failed: ${err instanceof Error ? err.message : 'Unknown error'}`);
    },
  });

  const assignMutation = useMutation({
    mutationFn: (adminId: string) => {
      if (!ticketId) throw new Error('No ticket selected.');
      return supportApi.assign(ticketId, adminId);
    },
    onSuccess: (res) => {
      if (!res.success) {
        setActionNote(`Assign failed: ${res.error ?? 'Unknown error'}`);
        return;
      }
      setAssigneeId('');
      setActionNote('Ticket assigned.');
      afterActionSuccess();
    },
    onError: (err: unknown) => {
      setActionNote(`Assign failed: ${err instanceof Error ? err.message : 'Unknown error'}`);
    },
  });

  const escalateMutation = useMutation({
    mutationFn: (reason: string) => {
      if (!ticketId) throw new Error('No ticket selected.');
      return supportApi.escalate(ticketId, reason);
    },
    onSuccess: (res) => {
      if (!res.success) {
        setActionNote(`Escalate failed: ${res.error ?? 'Unknown error'}`);
        return;
      }
      setActionNote('Ticket escalated.');
      afterActionSuccess();
    },
    onError: (err: unknown) => {
      setActionNote(`Escalate failed: ${err instanceof Error ? err.message : 'Unknown error'}`);
    },
  });

  const [escalateReason, setEscalateReason] = useState('');

  return (
    <Sheet open={Boolean(ticketId)} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full sm:max-w-lg">
        <SheetHeader>
          <SheetTitle>{ticket?.subject ?? (isLoading ? 'Loading ticket…' : 'Ticket')}</SheetTitle>
          <SheetDescription>
            {ticket ? `${ticket.userName} · ${ticket.category}` : 'Support ticket detail'}
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4">
          {isLoading && (
            <div className="flex flex-1 items-center justify-center py-10 text-muted-foreground">
              <Loader2 className="size-5 animate-spin" aria-hidden="true" />
            </div>
          )}

          {error && !isLoading && (
            <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
              {error}
            </div>
          )}

          {ticket && !isLoading && (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <StatusPill tone={statusPillTone(ticket.status)}>{statusLabel(ticket.status)}</StatusPill>
                <StatusPill tone={priorityPillTone(ticket.priority)}>{ticket.priority}</StatusPill>
                <Badge variant="outline">{ticket.userType}</Badge>
              </div>

              <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border bg-card p-3 text-sm">
                <dt className="text-muted-foreground">Assigned to</dt>
                <dd className="text-foreground">{ticket.assignedToName ?? 'Unassigned'}</dd>
                <dt className="text-muted-foreground">Created</dt>
                <dd className="text-foreground">{formatDateTime(ticket.createdAt)}</dd>
                <dt className="text-muted-foreground">Last updated</dt>
                <dd className="text-foreground">{formatDateTime(ticket.updatedAt)}</dd>
                {ticket.resolvedAt && (
                  <>
                    <dt className="text-muted-foreground">Resolved</dt>
                    <dd className="text-foreground">{formatDateTime(ticket.resolvedAt)}</dd>
                  </>
                )}
              </dl>

              {ticket.relatedEntities.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {ticket.relatedEntities.map((entity) => (
                    <Badge key={`${entity.type}-${entity.id}`} variant="secondary">
                      {entity.type}: {entity.name}
                    </Badge>
                  ))}
                </div>
              )}

              <div className="flex items-center gap-2 text-sm font-medium text-foreground">
                <MessageCircle className="size-4" aria-hidden="true" />
                Message thread
              </div>

              <ul className="flex flex-col gap-3">
                {ticket.messages.map((message) => (
                  <li
                    key={message.id}
                    className={cn(
                      'flex flex-col gap-1 rounded-lg border p-3 text-sm',
                      message.senderType === 'ADMIN'
                        ? 'border-primary/30 bg-primary/5'
                        : 'border-border bg-card',
                    )}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="inline-flex items-center gap-1.5 font-medium text-foreground">
                        <User className="size-3.5 text-muted-foreground" aria-hidden="true" />
                        {message.senderName}
                        {message.senderType === 'ADMIN' && (
                          <Badge variant="outline" className="ml-1">
                            Admin
                          </Badge>
                        )}
                      </span>
                      <span className="whitespace-nowrap text-xs text-muted-foreground">
                        {formatDateTime(message.createdAt)}
                      </span>
                    </div>
                    <p className="text-foreground">{message.content}</p>
                  </li>
                ))}
              </ul>

              {/* Reply composer — supportApi.reply(id, content) */}
              <div className="flex flex-col gap-2 border-t border-border pt-4">
                <label htmlFor="ticket-reply" className="text-sm font-medium text-foreground">
                  Reply
                </label>
                <Textarea
                  id="ticket-reply"
                  value={replyContent}
                  onChange={(e) => setReplyContent(e.target.value)}
                  placeholder="Write a reply to the requester…"
                  rows={3}
                  disabled={replyMutation.isPending}
                />
                <div className="flex justify-end">
                  <Button
                    type="button"
                    size="sm"
                    disabled={!replyContent.trim() || replyMutation.isPending}
                    onClick={() => replyMutation.mutate(replyContent.trim())}
                  >
                    {replyMutation.isPending ? (
                      <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                    ) : (
                      <Send className="size-4" aria-hidden="true" />
                    )}
                    Send reply
                  </Button>
                </div>
              </div>

              {/* Assign / Escalate — supportApi.assign(id, adminId) and
                  supportApi.escalate(id, reason). */}
              <div className="mt-auto flex flex-col gap-3 border-t border-border pt-4">
                <h3 className="text-sm font-semibold text-foreground">Actions</h3>

                {actionNote && (
                  <p className="rounded-lg border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
                    {actionNote}
                  </p>
                )}

                <div className="flex flex-wrap items-end gap-2">
                  <div className="flex min-w-40 flex-1 flex-col gap-1">
                    <label htmlFor="ticket-assignee" className="text-xs font-medium text-muted-foreground">
                      Assign to (admin ID)
                    </label>
                    <Input
                      id="ticket-assignee"
                      value={assigneeId}
                      onChange={(e) => setAssigneeId(e.target.value)}
                      placeholder="Admin ID"
                      disabled={assignMutation.isPending}
                    />
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={!assigneeId.trim() || assignMutation.isPending}
                    onClick={() => assignMutation.mutate(assigneeId.trim())}
                  >
                    {assignMutation.isPending ? (
                      <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                    ) : (
                      <UserPlus className="size-4" aria-hidden="true" />
                    )}
                    Assign
                  </Button>

                  <AlertDialog
                    onOpenChange={(open) => {
                      if (!open) setEscalateReason('');
                    }}
                  >
                    <AlertDialogTrigger asChild>
                      <Button type="button" size="sm" variant="destructive">
                        <TriangleAlert className="size-4" aria-hidden="true" />
                        Escalate
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>Escalate this ticket?</AlertDialogTitle>
                        <AlertDialogDescription>
                          This routes the ticket to a senior admin for further review. Provide a reason for the
                          audit trail.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <Textarea
                        value={escalateReason}
                        onChange={(e) => setEscalateReason(e.target.value)}
                        placeholder="Reason for escalation (e.g. SLA breach risk, needs specialist)"
                      />
                      <AlertDialogFooter>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction
                          disabled={!escalateReason.trim() || escalateMutation.isPending}
                          onClick={() => escalateMutation.mutate(escalateReason.trim())}
                        >
                          Escalate
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </div>
              </div>
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  );
}

// ============================================
// STATS STRIP — open / in-progress / waiting-user / avg response / avg
// resolution, backed by `useSupportStats()` (`supportApi.getStats()`).
// ============================================

function SupportStatsStrip() {
  const { data, isLoading, error } = useSupportStats();

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-3 text-xs text-destructive-foreground">
        Failed to load support stats: {error}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      <KpiCard title="Open" value={data?.open ?? 0} icon="support" isLoading={isLoading} />
      <KpiCard title="In Progress" value={data?.inProgress ?? 0} icon="support" isLoading={isLoading} />
      <KpiCard title="Waiting on User" value={data?.waitingUser ?? 0} icon="support" isLoading={isLoading} />
      <KpiCard
        title="Avg. Response"
        value={data ? formatDuration(data.avgResponseTime) : ''}
        isLoading={isLoading}
      />
      <KpiCard
        title="Avg. Resolution"
        value={data ? formatDuration(data.avgResolutionTime) : ''}
        isLoading={isLoading}
      />
    </div>
  );
}

// ============================================
// PROPS
// ============================================

export interface TicketListProps {
  className?: string;
}

// ============================================
// COMPONENT
// ============================================

export default function TicketList({ className }: TicketListProps) {
  const { tickets, totalCount, isLoading, error, filters, setFilters, sort, setSort, refetch } = useTicketList();
  const [selectedTicketId, setSelectedTicketId] = useState<string | null>(null);

  function handleSort(field: TicketSortField) {
    setSort({
      field,
      direction: sort.field === field && sort.direction === 'asc' ? 'desc' : 'asc',
    });
  }

  if (error) {
    return (
      <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
        Failed to load tickets: {error}
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-4', className)}>
      {/* Stats strip */}
      <SupportStatsStrip />

      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={filters.search ?? ''}
            onChange={(e) => setFilters({ ...filters, search: e.target.value || undefined })}
            placeholder="Search subject or requester…"
            className="sm:max-w-xs"
            aria-label="Search tickets"
          />

          <Select
            value={filters.status ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, status: value === 'ALL' ? undefined : (value as TicketStatus) })
            }
          >
            <SelectTrigger className="sm:w-40" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value={TicketStatus.OPEN}>Open</SelectItem>
              <SelectItem value={TicketStatus.IN_PROGRESS}>In Progress</SelectItem>
              <SelectItem value={TicketStatus.WAITING_USER}>Waiting User</SelectItem>
              <SelectItem value={TicketStatus.RESOLVED}>Resolved</SelectItem>
              <SelectItem value={TicketStatus.CLOSED}>Closed</SelectItem>
            </SelectContent>
          </Select>

          <Select
            value={filters.priority ?? 'ALL'}
            onValueChange={(value) =>
              setFilters({ ...filters, priority: value === 'ALL' ? undefined : (value as TicketPriority) })
            }
          >
            <SelectTrigger className="sm:w-36" aria-label="Filter by priority">
              <SelectValue placeholder="All priorities" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All priorities</SelectItem>
              <SelectItem value={TicketPriority.URGENT}>Urgent</SelectItem>
              <SelectItem value={TicketPriority.HIGH}>High</SelectItem>
              <SelectItem value={TicketPriority.MEDIUM}>Medium</SelectItem>
              <SelectItem value={TicketPriority.LOW}>Low</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${tickets.length} of ${totalCount} tickets`}
        </p>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <SortableHead field="subject" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Subject
              </SortableHead>
              <SortableHead field="userName" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Requester
              </SortableHead>
              <SortableHead field="status" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Status
              </SortableHead>
              <SortableHead field="priority" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Priority
              </SortableHead>
              <SortableHead
                field="assignedToName"
                activeField={sort.field}
                direction={sort.direction}
                onSort={handleSort}
              >
                Assigned To
              </SortableHead>
              <SortableHead field="updatedAt" activeField={sort.field} direction={sort.direction} onSort={handleSort}>
                Last Updated
              </SortableHead>
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
            ) : tickets.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Inbox className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No tickets match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              tickets.map((ticket: SupportTicket) => (
                <TableRow
                  key={ticket.id}
                  onClick={() => setSelectedTicketId(ticket.id)}
                  className="cursor-pointer"
                  tabIndex={0}
                  role="button"
                  aria-label={`Open ticket: ${ticket.subject}`}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelectedTicketId(ticket.id);
                    }
                  }}
                >
                  <TableCell className="max-w-72 whitespace-normal font-medium text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <LifeBuoy className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                      {ticket.subject}
                    </span>
                    <Badge variant="outline" className="ml-2 align-middle">
                      {ticket.category}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    <div className="flex flex-col">
                      <span className="text-foreground">{ticket.userName}</span>
                      <span className="text-xs">{ticket.userType}</span>
                    </div>
                  </TableCell>
                  <TableCell>
                    <StatusPill tone={statusPillTone(ticket.status)}>{statusLabel(ticket.status)}</StatusPill>
                  </TableCell>
                  <TableCell>
                    <StatusPill tone={priorityPillTone(ticket.priority)}>{ticket.priority}</StatusPill>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{ticket.assignedToName ?? 'Unassigned'}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {formatDateTime(ticket.updatedAt)}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <TicketDetailDrawer
        ticketId={selectedTicketId}
        onOpenChange={(open) => {
          if (!open) setSelectedTicketId(null);
        }}
        onActionSuccess={refetch}
      />
    </div>
  );
}
