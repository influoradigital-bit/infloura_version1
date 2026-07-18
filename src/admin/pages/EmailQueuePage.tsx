/**
 * INFLUORA ADMIN PANEL — Email Queue Page
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass. Mounted at /admin/emails by src/pages/admin-console.tsx.
 *
 * Stats header + status-filtered queue table + templates list, backed by
 * `useEmailQueue()` (src/admin/hooks/useEmailQueue.ts), wired to
 * `emailApi.getQueue()` / `.getStats()` / `.getTemplates()` / `.retry()`.
 *
 * Bulk-send is rendered as a disabled control with an explanatory tooltip —
 * the backend (`emailApi.sendBulk`) returns 501 pending abuse controls, so
 * this page never calls it (see useEmailQueue.ts doc comment).
 */

import { useState, type ReactNode } from 'react';
import { Mail, Loader2, RotateCw, Ban } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { useEmailQueue } from '../hooks/useEmailQueue';
import KpiCard from '../components/dashboard/KpiCard';
import { EmailStatus, type EmailQueueItem } from '../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/**
 * `avgDeliveryTime` (seconds, presumed) has no distinct "not measured yet"
 * sentinel on the wire — a fresh/quiet queue with nothing delivered yet
 * legitimately reports `0`. Rendered as "No data yet" rather than a
 * misleading "0s" (honest-empty state, same rationale as
 * `TicketList.tsx`'s formatDuration for avgResponseTime/avgResolutionTime).
 */
function formatDeliveryTime(seconds: number): string {
  if (seconds <= 0) return 'No data yet';
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return rest > 0 ? `${minutes}m ${rest}s` : `${minutes}m`;
}

// ============================================
// STATUS PILL — solid, AA-legible fill, same pattern as
// TicketList/CampaignTable/AuditLogPage per prior brand-CTA-contrast
// feedback.
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

function emailStatusTone(status: EmailStatus): PillTone {
  switch (status) {
    case EmailStatus.SENT:
      return 'success';
    case EmailStatus.FAILED:
      return 'destructive';
    case EmailStatus.RETRYING:
      return 'warning';
    case EmailStatus.PENDING:
    default:
      return 'neutral';
  }
}

// ============================================
// RETRY ACTION — per-row.
// ============================================

function RetryButton({
  item,
  onRetry,
}: {
  item: EmailQueueItem;
  onRetry: (id: string) => Promise<{ success: boolean; error?: string }>;
}) {
  const [isRetrying, setIsRetrying] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  if (item.status !== EmailStatus.FAILED) {
    return null;
  }

  async function handleClick() {
    setIsRetrying(true);
    setLocalError(null);
    const res = await onRetry(item.id);
    if (!res.success) {
      setLocalError(res.error ?? 'Failed to retry.');
    }
    setIsRetrying(false);
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <Button type="button" size="sm" variant="outline" disabled={isRetrying} onClick={() => void handleClick()}>
        {isRetrying ? (
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
        ) : (
          <RotateCw className="size-3.5" aria-hidden="true" />
        )}
        Retry
      </Button>
      {localError && <span className="text-xs text-destructive-foreground">{localError}</span>}
    </div>
  );
}

// ============================================
// PAGE CONTROLS — same shape as UsersPage.tsx's local PageControls.
// ============================================

function PageControls({
  page,
  totalPages,
  onPageChange,
}: {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  if (totalPages <= 1) return null;

  return (
    <div className="flex items-center justify-between border-t border-border pt-3">
      <Button type="button" variant="outline" size="sm" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>
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
        onClick={() => onPageChange(page + 1)}
      >
        Next
      </Button>
    </div>
  );
}

// ============================================
// PAGE
// ============================================

export default function EmailQueuePage() {
  const {
    items,
    totalCount,
    totalPages,
    stats,
    templates,
    isLoading,
    error,
    statusFilter,
    setStatusFilter,
    page,
    setPage,
    retry,
  } = useEmailQueue();

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-start">
        <div>
          <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
            <Mail className="size-6" aria-hidden="true" />
            Email Queue
          </h2>
          <p className="text-sm text-muted-foreground">
            Transactional email delivery — status, retries, and templates.
          </p>
        </div>

        {/* Bulk-send — disabled pending abuse controls (backend returns 501).
            Never wired to emailApi.sendBulk(). */}
        <Tooltip>
          <TooltipTrigger asChild>
            <span tabIndex={0}>
              <Button type="button" variant="outline" disabled aria-disabled="true">
                <Ban aria-hidden="true" />
                Bulk Send
              </Button>
            </span>
          </TooltipTrigger>
          <TooltipContent>Disabled — pending abuse controls</TooltipContent>
        </Tooltip>
      </div>

      {error && (
        <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load email queue: {error}
        </Card>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <KpiCard title="Sent (24h)" value={stats?.sent24h ?? 0} icon="support" isLoading={isLoading} />
        <KpiCard title="Failed (24h)" value={stats?.failed24h ?? 0} icon="risk" isLoading={isLoading} />
        <KpiCard title="Pending" value={stats?.pending ?? 0} icon="support" isLoading={isLoading} />
        <KpiCard
          title="Avg. Delivery"
          value={stats ? formatDeliveryTime(stats.avgDeliveryTime) : ''}
          isLoading={isLoading}
        />
      </div>

      {/* Templates */}
      <Card className="gap-3 p-5">
        <h3 className="text-sm font-semibold text-foreground">Templates</h3>
        {isLoading ? (
          <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        ) : templates.length === 0 ? (
          <p className="text-sm text-muted-foreground">No templates on file.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {templates.map((t) => (
              <li key={t.id}>
                <Badge variant="outline" className="whitespace-nowrap" title={t.subject}>
                  {t.name}
                </Badge>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* Filters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <Select
          value={statusFilter ?? 'ALL'}
          onValueChange={(value) => setStatusFilter(value === 'ALL' ? undefined : value)}
        >
          <SelectTrigger className="sm:w-44" aria-label="Filter by status">
            <SelectValue placeholder="All statuses" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value={EmailStatus.PENDING}>Pending</SelectItem>
            <SelectItem value={EmailStatus.SENT}>Sent</SelectItem>
            <SelectItem value={EmailStatus.FAILED}>Failed</SelectItem>
            <SelectItem value={EmailStatus.RETRYING}>Retrying</SelectItem>
          </SelectContent>
        </Select>

        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${items.length} of ${totalCount} queued emails`}
        </p>
      </div>

      {/* Table */}
      <div className="overflow-x-auto rounded-lg border border-border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Recipient</TableHead>
              <TableHead>Template</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Retries</TableHead>
              <TableHead>Scheduled</TableHead>
              <TableHead>Sent</TableHead>
              <TableHead>Action</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 8 }).map((_, i) => (
                <TableRow key={i}>
                  {Array.from({ length: 7 }).map((__, j) => (
                    <TableCell key={j}>
                      <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                    </TableCell>
                  ))}
                </TableRow>
              ))
            ) : items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="py-10 text-center text-sm text-muted-foreground">
                  <div className="flex flex-col items-center gap-2">
                    <Mail className="size-6 text-muted-foreground/60" aria-hidden="true" />
                    No queued emails match the current filter.
                  </div>
                </TableCell>
              </TableRow>
            ) : (
              items.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="max-w-56 whitespace-normal text-foreground">{item.recipient}</TableCell>
                  <TableCell className="text-muted-foreground">{item.templateName}</TableCell>
                  <TableCell>
                    <StatusPill tone={emailStatusTone(item.status)}>{item.status}</StatusPill>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{item.retryCount}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {formatDateTime(item.scheduledAt)}
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground">
                    {formatDateTime(item.sentAt)}
                  </TableCell>
                  <TableCell>
                    <RetryButton item={item} onRetry={retry} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <PageControls page={page} totalPages={totalPages} onPageChange={setPage} />
    </div>
  );
}
