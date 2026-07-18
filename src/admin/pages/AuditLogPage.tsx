/**
 * INFLUORA ADMIN PANEL — Audit Log Page
 * Owner: Ananya (Frontend)
 * Reference: A6 (Priya-routed admin-panel audit fix). Mounted at
 * /admin/audit by src/pages/admin-console.tsx.
 *
 * Thin page shell (header + description) around a paginated table of
 * `AuditLogEntry` rows (src/admin/types/admin.types.ts), same split as
 * `DisputesPage.tsx` around `DisputeList`. Data + server-side filter/page
 * state comes from `useAuditLog()` (src/admin/hooks/useAuditLog.ts), wired
 * to the previously-unused `auditApi.list()` call.
 */

import { type FormEvent, useState } from 'react';
import { ScrollText } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { useAuditLog, type AuditLogFilters } from '../hooks/useAuditLog';
import type { AuditLogEntry, AuditLogSource } from '../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

function formatChange(entry: AuditLogEntry): string {
  if (!entry.oldValue && !entry.newValue) return '—';
  return `${entry.oldValue ?? '—'} → ${entry.newValue ?? '—'}`;
}

// ============================================
// SOURCE PILL — Kabir finding 2.1: a client-submitted row must be visibly
// distinct from an authoritative server-written row, so a super-admin can't
// pass a fabricated entry off as authoritative. Solid, AA-legible fill (not a
// washed-out pastel badge) — same StatusPill pattern as
// DisputeList/FlagQueue/TicketList/CampaignTable, per prior brand-CTA-contrast
// feedback. Text label carries the meaning (not color-only); `aria-label`
// spells out the security implication for screen readers.
// ============================================

const SOURCE_PILL_CLASSES: Record<AuditLogSource, string> = {
  SERVER_INTERNAL: 'bg-foreground',
  CLIENT_REPORTED: 'bg-warning-foreground',
};

const SOURCE_LABEL: Record<AuditLogSource, string> = {
  SERVER_INTERNAL: 'System',
  CLIENT_REPORTED: 'Reported',
};

const SOURCE_ARIA_LABEL: Record<AuditLogSource, string> = {
  SERVER_INTERNAL: 'Source: system — written directly by the server, authoritative.',
  CLIENT_REPORTED: 'Source: client-reported — submitted by a client, not independently verified by the server.',
};

function SourcePill({ source }: { source: AuditLogSource }) {
  return (
    <span
      title={SOURCE_ARIA_LABEL[source]}
      aria-label={SOURCE_ARIA_LABEL[source]}
      className={cn(
        'inline-flex w-fit items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold text-white',
        SOURCE_PILL_CLASSES[source],
      )}
    >
      {SOURCE_LABEL[source]}
    </span>
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
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={page <= 1}
        onClick={() => onPageChange(page - 1)}
      >
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
// FILTER BAR — entityType/action free-text + a date range. All five fields
// `auditApi.list()` accepts (adminId/entityType/action/startDate/endDate)
// are supported by the hook; `adminId` is omitted from the UI for now
// (no admin directory/picker exists yet to resolve a friendly name to an id).
// ============================================

function FilterBar({
  filters,
  onApply,
}: {
  filters: AuditLogFilters;
  onApply: (filters: AuditLogFilters) => void;
}) {
  const [entityType, setEntityType] = useState(filters.entityType ?? '');
  const [action, setAction] = useState(filters.action ?? '');
  const [startDate, setStartDate] = useState(filters.startDate ?? '');
  const [endDate, setEndDate] = useState(filters.endDate ?? '');

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    onApply({
      entityType: entityType.trim() || undefined,
      action: action.trim() || undefined,
      startDate: startDate || undefined,
      endDate: endDate || undefined,
    });
  }

  function handleClear() {
    setEntityType('');
    setAction('');
    setStartDate('');
    setEndDate('');
    onApply({});
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
      <div className="flex flex-col gap-1">
        <label htmlFor="audit-entity-type" className="text-xs font-medium text-muted-foreground">
          Entity type
        </label>
        <Input
          id="audit-entity-type"
          value={entityType}
          onChange={(e) => setEntityType(e.target.value)}
          placeholder="e.g. BRAND, CAMPAIGN…"
          className="sm:w-40"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label htmlFor="audit-action" className="text-xs font-medium text-muted-foreground">
          Action
        </label>
        <Input
          id="audit-action"
          value={action}
          onChange={(e) => setAction(e.target.value)}
          placeholder="e.g. brand.suspend…"
          className="sm:w-48"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label htmlFor="audit-start-date" className="text-xs font-medium text-muted-foreground">
          From
        </label>
        <Input
          id="audit-start-date"
          type="date"
          value={startDate}
          onChange={(e) => setStartDate(e.target.value)}
          className="sm:w-40"
        />
      </div>

      <div className="flex flex-col gap-1">
        <label htmlFor="audit-end-date" className="text-xs font-medium text-muted-foreground">
          To
        </label>
        <Input
          id="audit-end-date"
          type="date"
          value={endDate}
          onChange={(e) => setEndDate(e.target.value)}
          className="sm:w-40"
        />
      </div>

      <div className="flex items-center gap-2">
        <Button type="submit" size="sm" aria-label="Apply audit log filters">
          Apply
        </Button>
        <Button type="button" size="sm" variant="outline" onClick={handleClear}>
          Clear
        </Button>
      </div>
    </form>
  );
}

// ============================================
// PAGE
// ============================================

export default function AuditLogPage() {
  const { entries, totalCount, totalPages, isLoading, error, filters, setFilters, page, setPage } = useAuditLog();

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <ScrollText className="size-6" aria-hidden="true" />
          Audit Log
        </h2>
        <p className="text-sm text-muted-foreground">
          Every admin mutation — KYC decisions, suspensions, fee changes, dispute resolutions, and
          more — with who did it, when, and what changed.
        </p>
      </div>

      <Card className="p-4">
        <FilterBar filters={filters} onApply={setFilters} />
      </Card>

      {error && (
        <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load audit log: {error}
        </Card>
      )}

      <div className="flex flex-col gap-4">
        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${entries.length} of ${totalCount} entries`}
        </p>

        <div className="overflow-x-auto rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Timestamp</TableHead>
                <TableHead>Admin</TableHead>
                <TableHead>Action</TableHead>
                <TableHead>Entity</TableHead>
                <TableHead>Change</TableHead>
                <TableHead>Reason</TableHead>
                <TableHead>IP Address</TableHead>
                <TableHead>Source</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 8 }).map((__, j) => (
                      <TableCell key={j}>
                        <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : entries.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className="py-10 text-center text-sm text-muted-foreground">
                    <div className="flex flex-col items-center gap-2">
                      <ScrollText className="size-6 text-muted-foreground/60" aria-hidden="true" />
                      No audit log entries match the current filters.
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                entries.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatDateTime(entry.timestamp)}
                    </TableCell>
                    <TableCell className="max-w-48 whitespace-normal font-medium text-foreground">
                      {entry.adminEmail}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-foreground">{entry.action}</TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {entry.entityType}
                      <div className="text-xs text-muted-foreground/80">{entry.entityId}</div>
                    </TableCell>
                    <TableCell className="max-w-64 whitespace-normal text-muted-foreground">
                      {formatChange(entry)}
                    </TableCell>
                    <TableCell className="max-w-56 whitespace-normal text-muted-foreground">
                      {entry.reason ?? '—'}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{entry.ipAddress}</TableCell>
                    <TableCell>
                      <SourcePill source={entry.source} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>

        <PageControls page={page} totalPages={totalPages} onPageChange={setPage} />
      </div>
    </div>
  );
}
