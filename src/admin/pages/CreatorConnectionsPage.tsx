/**
 * INFLUORA ADMIN PANEL — Creator Connections
 * Owner: Ananya (Frontend)
 * Reference: T-CREATORCONNECT-0902 (.proof-os/tasks/T-CREATORCONNECT-0902/TASKS.md)
 *
 * A brand's "Connect this creator" enquiry (Discover → Instagram tab) lands here for admin
 * follow-up: contact the creator off-platform, invite them by email, or decline. A second
 * section imports Instagram handles the admin already has by hand (Business Discovery
 * enrichment, ADMIN_IMPORT source) into the same `external_creators` table Discover reads from.
 *
 * Same table+Sheet-drawer / reason-required-action shell as FlagQueue.tsx (moderation), and the
 * same manual-URLSearchParams pagination as AuditLogPage/UsersPage's local PageControls. Backed
 * by `creatorConnectionsApi` (src/admin/services/api-contracts.ts), itself following
 * `moderationApi`'s `apiRequest` pattern against the real `AdminCreatorConnectionController`.
 */

import { type FormEvent, type ReactNode, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CheckCircle2,
  Loader2,
  Mail,
  ShieldAlert,
  ShieldCheck,
  UserPlus,
  Users,
  XCircle,
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
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { cn } from '@/lib/utils';
import { useToast } from '@/hooks/use-toast';
import { creatorConnectionsApi } from '../services/api-contracts';
import type { AdminConnection, CreatorConnectionRequestStatus } from '../types/admin.types';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PAGE_SIZE = 20;

function formatDateTime(iso: string | null): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/** Never coerce a nullable count to 0 — an unenriched row genuinely has no known follower count. */
function formatFollowers(n: number | null): string {
  if (n == null) return '—';
  if (n >= 10000000) return `${(n / 10000000).toFixed(1)}Cr`;
  if (n >= 100000) return `${(n / 100000).toFixed(1)}L`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return String(n);
}

// ============================================
// STATUS PILL — solid AA-legible fill, same convention as FlagQueue's StatusPill.
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

function connectionTone(status: CreatorConnectionRequestStatus): PillTone {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'CONTACTED':
      return 'neutral';
    case 'JOINED':
      return 'success';
    case 'DECLINED':
    default:
      return 'destructive';
  }
}

function connectionLabel(status: CreatorConnectionRequestStatus): string {
  return status[0] + status.slice(1).toLowerCase();
}

/** Generic Title Case for any UPPER_SNAKE status string (used for external-creator statuses,
 *  which are a different enum than `CreatorConnectionRequestStatus`). */
function titleCaseStatus(status: string): string {
  return status[0] + status.slice(1).toLowerCase();
}

// ============================================
// ACTION DIALOG — shared shell for Mark contacted / Decline (notes optional) and
// Invite (email required, notes optional). Same reason-in-a-Textarea pattern as FlagQueue's
// ModerationActionButton, extended with a required-email variant.
// ============================================

type ActionKind = 'contacted' | 'decline' | 'invite';

interface ActionDialogConfig {
  kind: ActionKind;
  title: string;
  description: string;
  confirmLabel: string;
  variant: 'default' | 'destructive' | 'outline';
}

function ConnectionActionDialog({
  connection,
  config,
  open,
  onOpenChange,
  onSubmit,
  isPending,
  error,
}: {
  connection: AdminConnection;
  config: ActionDialogConfig;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (payload: { notes?: string; email?: string }) => void;
  isPending: boolean;
  /** Server-side or network failure from the last submit attempt. Non-null keeps the dialog
   *  open with the reason visible instead of closing silently (Q4.1). */
  error?: string | null;
}) {
  const [notes, setNotes] = useState('');
  const [email, setEmail] = useState(connection.creatorEmail ?? '');
  const requiresEmail = config.kind === 'invite';
  const emailValid = !requiresEmail || EMAIL_RE.test(email.trim());

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!emailValid) return;
    onSubmit({ notes: notes.trim() || undefined, email: requiresEmail ? email.trim() : undefined });
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setNotes('');
          setEmail(connection.creatorEmail ?? '');
        }
        onOpenChange(next);
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{config.title}</DialogTitle>
          <DialogDescription>{config.description}</DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="flex flex-col gap-3">
          <div className="rounded-lg border border-border bg-card p-3 text-sm">
            <p className="font-medium text-foreground">
              {connection.igUsername ? `@${connection.igUsername}` : '—'}
            </p>
            <p className="text-muted-foreground">{connection.brandName || '—'}</p>
          </div>
          {requiresEmail && (
            <div className="flex flex-col gap-1.5">
              <label htmlFor="cc-invite-email" className="text-sm font-medium text-foreground">
                Creator email
              </label>
              <Input
                id="cc-invite-email"
                type="email"
                required
                placeholder="creator@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
              {email.trim().length > 0 && !emailValid && (
                <p className="text-xs text-destructive-foreground">Enter a valid email address.</p>
              )}
            </div>
          )}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="cc-action-notes" className="text-sm font-medium text-foreground">
              Notes (optional)
            </label>
            <Textarea
              id="cc-action-notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value.slice(0, 1000))}
              maxLength={1000}
              placeholder="Internal note for the audit trail…"
              rows={3}
            />
          </div>
          {error && (
            <p role="alert" className="text-sm text-destructive-foreground">
              {error}
            </p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" variant={config.variant} disabled={isPending || !emailValid}>
              {isPending ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden="true" /> : null}
              {config.confirmLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

// ============================================
// MAIN PAGE
// ============================================

export default function CreatorConnectionsPage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [status, setStatus] = useState<CreatorConnectionRequestStatus | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [activeAction, setActiveAction] = useState<{ connection: AdminConnection; kind: ActionKind } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [expandedMessage, setExpandedMessage] = useState<string | null>(null);
  const [importText, setImportText] = useState('');
  const [importResult, setImportResult] = useState<{ imported: number; enriched: number; skipped: string[] } | null>(
    null,
  );
  const [importError, setImportError] = useState<string | null>(null);

  const listQuery = useQuery({
    queryKey: ['admin', 'creator-connections', status, search, page],
    queryFn: () =>
      creatorConnectionsApi.list({
        status: status === 'ALL' ? undefined : status,
        search: search || undefined,
        page,
        pageSize: PAGE_SIZE,
      }),
  });

  const externalCreatorsQuery = useQuery({
    queryKey: ['admin', 'external-creators'],
    queryFn: () => creatorConnectionsApi.externalCreators({ page: 1, pageSize: 20 }),
  });

  const actionMutation = useMutation({
    mutationFn: async (input: { kind: ActionKind; id: string; notes?: string; email?: string }) => {
      if (input.kind === 'contacted') return creatorConnectionsApi.markContacted(input.id, input.notes);
      if (input.kind === 'decline') return creatorConnectionsApi.decline(input.id, input.notes);
      return creatorConnectionsApi.invite(input.id, { email: input.email!, notes: input.notes });
    },
    onSuccess: (res, variables) => {
      // apiRequest never throws on a non-2xx response — it resolves { success: false, error }
      // (api-contracts.ts:95-105). Q4.1: this branch used to close the dialog unconditionally,
      // so a 404/409/500 looked identical to a real invite/contact/decline to the admin.
      if (!res.success) {
        setActionError(res.error ?? 'Request failed. Please try again.');
        return;
      }
      queryClient.invalidateQueries({ queryKey: ['admin', 'creator-connections'] });
      if (variables.kind === 'invite') {
        queryClient.invalidateQueries({ queryKey: ['admin', 'external-creators'] });
        toast({ title: 'Invitation sent', description: `Invitation sent to ${variables.email}.` });
      }
      setActionError(null);
      setActiveAction(null);
    },
    onError: (err: Error) => {
      // fetch() itself rejects on a network failure (offline, DNS, CORS) — apiRequest does not
      // catch that, so it reaches here rather than resolving { success: false }.
      setActionError(err.message || 'Request failed. Please try again.');
    },
  });

  function openAction(connection: AdminConnection, kind: ActionKind) {
    setActionError(null);
    setActiveAction({ connection, kind });
  }

  const importMutation = useMutation({
    mutationFn: (usernames: string[]) => creatorConnectionsApi.importHandles(usernames),
    onSuccess: (res) => {
      if (res.success && res.data) {
        setImportResult(res.data);
        setImportError(null);
        queryClient.invalidateQueries({ queryKey: ['admin', 'external-creators'] });
      } else {
        setImportError(res.error ?? 'Import failed.');
      }
    },
    onError: (err: Error) => setImportError(err.message || 'Import failed.'),
  });

  function handleImport() {
    const usernames = importText
      .split('\n')
      .map((s) => s.trim().replace(/^@/, ''))
      .filter(Boolean)
      .slice(0, 50);
    if (usernames.length === 0) return;
    setImportError(null);
    setImportResult(null);
    importMutation.mutate(usernames);
  }

  const rows = listQuery.data?.success ? listQuery.data.data?.items ?? [] : [];
  const total = listQuery.data?.success ? listQuery.data.data?.total ?? 0 : 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const loadError = !listQuery.isLoading && listQuery.data && !listQuery.data.success ? listQuery.data.error : null;

  function actionDialogConfig(kind: ActionKind): ActionDialogConfig {
    switch (kind) {
      case 'contacted':
        return {
          kind,
          title: 'Mark as contacted',
          description: 'Confirm you have reached out to this creator off-platform.',
          confirmLabel: 'Mark contacted',
          variant: 'outline',
        };
      case 'decline':
        return {
          kind,
          title: 'Decline this request',
          description: 'The brand will not be told why — use notes for the internal record.',
          confirmLabel: 'Decline',
          variant: 'destructive',
        };
      case 'invite':
      default:
        return {
          kind,
          title: 'Invite to Influora',
          description: 'Sends the creator a join-Influora email with a signup link for this handle.',
          confirmLabel: 'Send invitation',
          variant: 'default',
        };
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <UserPlus className="size-6" aria-hidden="true" />
          Creator connections
        </h2>
        <p className="text-sm text-muted-foreground">
          Brands asking to work with Instagram creators who aren&apos;t on Influora yet. Reach out,
          then invite them — they&apos;ll show as &quot;Verified with Influora&quot; once they join.
        </p>
      </div>

      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-1 flex-col gap-2 sm:flex-row sm:items-center">
          <Input
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(1);
            }}
            placeholder="Search handle or brand…"
            className="sm:max-w-xs"
            aria-label="Search creator connections"
          />
          <Select
            value={status}
            onValueChange={(value) => {
              setStatus(value as CreatorConnectionRequestStatus | 'ALL');
              setPage(1);
            }}
          >
            <SelectTrigger className="sm:w-40" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value="PENDING">Pending</SelectItem>
              <SelectItem value="CONTACTED">Contacted</SelectItem>
              <SelectItem value="JOINED">Joined</SelectItem>
              <SelectItem value="DECLINED">Declined</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <span className="whitespace-nowrap text-sm text-muted-foreground">
          {listQuery.isLoading ? 'Loading…' : `${rows.length} of ${total} requests`}
        </span>
      </div>

      {loadError && (
        <div className="rounded-lg border border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load creator connections: {loadError}
        </div>
      )}

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Brand</TableHead>
              <TableHead>Creator</TableHead>
              <TableHead>Message</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Requested</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {listQuery.isLoading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 6 }).map((__, j) => (
                    <TableCell key={j}>
                      <div className="h-4 w-full max-w-28 animate-pulse rounded bg-muted" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Users className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No creator connection requests match the current filters.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              rows.map((c) => {
                const canAct = c.status === 'PENDING' || c.status === 'CONTACTED';
                const messageExpanded = expandedMessage === c.id;
                return (
                  <TableRow key={c.id}>
                    <TableCell className="text-foreground">{c.brandName || '—'}</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <Avatar className="h-8 w-8 shrink-0">
                          <AvatarImage src={c.avatarUrl || undefined} />
                          <AvatarFallback>
                            {c.igUsername ? c.igUsername.charAt(0).toUpperCase() : '?'}
                          </AvatarFallback>
                        </Avatar>
                        <div className="min-w-0">
                          <p className="truncate font-medium text-foreground">
                            {c.igUsername ? `@${c.igUsername}` : '—'}
                          </p>
                          <p className="truncate text-xs text-muted-foreground">
                            {formatFollowers(c.followers)} followers
                          </p>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="max-w-64 text-muted-foreground">
                      {c.message ? (
                        <button
                          type="button"
                          onClick={() => setExpandedMessage(messageExpanded ? null : c.id)}
                          className={cn('text-left hover:underline', !messageExpanded && 'line-clamp-2')}
                        >
                          {c.message}
                        </button>
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell>
                      <StatusPill tone={connectionTone(c.status)}>{connectionLabel(c.status)}</StatusPill>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatDateTime(c.createdAt)}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          disabled={!canAct}
                          onClick={() => openAction(c, 'contacted')}
                        >
                          <CheckCircle2 aria-hidden="true" />
                          Mark contacted
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          disabled={!canAct}
                          onClick={() => openAction(c, 'invite')}
                        >
                          <Mail aria-hidden="true" />
                          Invite
                        </Button>
                        <Button
                          type="button"
                          size="sm"
                          variant="destructive"
                          disabled={!canAct}
                          onClick={() => openAction(c, 'decline')}
                        >
                          <XCircle aria-hidden="true" />
                          Decline
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
      </div>

      {totalPages > 1 && (
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
            disabled={page >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      )}

      {activeAction && (
        <ConnectionActionDialog
          connection={activeAction.connection}
          config={actionDialogConfig(activeAction.kind)}
          open={Boolean(activeAction)}
          onOpenChange={(open) => {
            if (!open) {
              setActiveAction(null);
              setActionError(null);
            }
          }}
          isPending={actionMutation.isPending}
          error={actionError}
          onSubmit={(payload) => {
            setActionError(null);
            actionMutation.mutate({
              kind: activeAction.kind,
              id: activeAction.connection.id,
              notes: payload.notes,
              email: payload.email,
            });
          }}
        />
      )}

      {/* Import Instagram creators */}
      <div className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
        <div>
          <h3 className="text-base font-semibold text-foreground">Import Instagram creators</h3>
          <p className="text-sm text-muted-foreground">
            One handle per line, up to 50. Each is enriched via Business Discovery where possible;
            otherwise it&apos;s added as a username-only stub.
          </p>
        </div>
        <Textarea
          value={importText}
          onChange={(e) => setImportText(e.target.value)}
          placeholder={'foodie.mumbai\ntravel.with.raj'}
          rows={4}
        />
        <div className="flex items-center gap-3">
          <Button type="button" onClick={handleImport} disabled={importMutation.isPending || !importText.trim()}>
            {importMutation.isPending ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden="true" /> : null}
            Import
          </Button>
          {importResult && (
            <p className="text-sm text-muted-foreground">
              Imported {importResult.imported}, enriched {importResult.enriched}
              {importResult.skipped.length > 0 ? `, skipped ${importResult.skipped.length}` : ''}.
            </p>
          )}
          {importError && <p className="text-sm text-destructive-foreground">{importError}</p>}
        </div>

        {/* External creators table — status of everything sourced so far. */}
        <div className="mt-2 overflow-hidden rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Creator</TableHead>
                <TableHead>Source</TableHead>
                <TableHead>Followers</TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {externalCreatorsQuery.isLoading ? (
                <TableRow>
                  <TableCell colSpan={4} className="py-6 text-center text-sm text-muted-foreground">
                    Loading…
                  </TableCell>
                </TableRow>
              ) : externalCreatorsQuery.data?.success && (externalCreatorsQuery.data.data?.items.length ?? 0) > 0 ? (
                externalCreatorsQuery.data.data!.items.map((ec) => (
                  <TableRow key={ec.id}>
                    <TableCell className="text-foreground">@{ec.igUsername}</TableCell>
                    <TableCell className="text-muted-foreground">{ec.source}</TableCell>
                    <TableCell className="text-muted-foreground">{formatFollowers(ec.followers)}</TableCell>
                    <TableCell>
                      {ec.status === 'JOINED' ? (
                        <span className="inline-flex items-center gap-1 text-success-foreground">
                          <ShieldCheck className="size-3.5" aria-hidden="true" /> Verified with Influora
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-warning-foreground">
                          <ShieldAlert className="size-3.5" aria-hidden="true" /> {titleCaseStatus(ec.status)}
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                ))
              ) : (
                <TableRow>
                  <TableCell colSpan={4} className="py-6 text-center text-sm text-muted-foreground">
                    No external creators sourced yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}
