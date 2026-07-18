/**
 * INFLUORA ADMIN PANEL — Error Log Page
 * Owner: Ananya (Frontend)
 * Reference: Wire-up pass. Mounted at /admin/errors by src/pages/admin-console.tsx.
 *
 * Thin page shell (header + stats strip + table) around recent
 * `ErrorLogEntry` rows, same split as `AuditLogPage.tsx`. Data comes from
 * `useErrorLog()` (src/admin/hooks/useErrorLog.ts), wired to
 * `errorApi.getRecent()` / `errorApi.getStats()` / `errorApi.resolve()`.
 */

import { useState, type ReactNode } from 'react';
import { AlertOctagon, CheckCircle2, Loader2 } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';
import { useErrorLog } from '../hooks/useErrorLog';
import KpiCard from '../components/dashboard/KpiCard';
import { ErrorSeverity, type ErrorLogEntry } from '../types/admin.types';

// ============================================
// FORMATTING
// ============================================

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

// ============================================
// SEVERITY PILL — solid, AA-legible fill (not a washed-out pastel badge),
// same pattern as AuditLogPage/CampaignTable/BrandProfile per prior
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

function severityTone(severity: ErrorSeverity): PillTone {
  switch (severity) {
    case ErrorSeverity.CRITICAL:
      return 'destructive';
    case ErrorSeverity.ERROR:
      return 'warning';
    case ErrorSeverity.WARN:
    default:
      return 'neutral';
  }
}

// ============================================
// RESOLVE ACTION — per-row, no reason required (not a moderation/compliance
// mutation like suspend/tier-adjust — just marks the error triaged).
// ============================================

function ResolveButton({
  entry,
  onResolve,
}: {
  entry: ErrorLogEntry;
  onResolve: (id: string) => Promise<{ success: boolean; error?: string }>;
}) {
  const [isResolving, setIsResolving] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  if (entry.resolved) {
    return (
      <StatusPill tone="success">
        <CheckCircle2 className="size-3.5" aria-hidden="true" />
        Resolved
      </StatusPill>
    );
  }

  async function handleClick() {
    setIsResolving(true);
    setLocalError(null);
    const res = await onResolve(entry.id);
    if (!res.success) {
      setLocalError(res.error ?? 'Failed to resolve.');
    }
    setIsResolving(false);
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <Button type="button" size="sm" variant="outline" disabled={isResolving} onClick={() => void handleClick()}>
        {isResolving ? <Loader2 className="size-3.5 animate-spin" aria-hidden="true" /> : null}
        Resolve
      </Button>
      {localError && <span className="text-xs text-destructive-foreground">{localError}</span>}
    </div>
  );
}

// ============================================
// PAGE
// ============================================

export default function ErrorLogPage() {
  const { entries, stats, isLoading, error, resolve } = useErrorLog();

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h2 className="flex items-center gap-2 text-2xl font-semibold text-foreground">
          <AlertOctagon className="size-6" aria-hidden="true" />
          Error Log
        </h2>
        <p className="text-sm text-muted-foreground">
          Recent server errors across the platform, with severity, endpoint, and resolution status.
        </p>
      </div>

      {error && (
        <Card className="border-destructive-foreground/30 bg-card p-4 text-sm text-destructive-foreground">
          Failed to load error log: {error}
        </Card>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <KpiCard title="Errors (24h)" value={stats?.total24h ?? 0} icon="risk" isLoading={isLoading} />
        <KpiCard title="Critical (24h)" value={stats?.critical24h ?? 0} icon="risk" isLoading={isLoading} />
        <KpiCard title="Unresolved" value={stats?.unresolved ?? 0} icon="risk" isLoading={isLoading} />
      </div>

      {/* Top endpoints */}
      <Card className="gap-3 p-5">
        <h3 className="text-sm font-semibold text-foreground">Top Endpoints (24h)</h3>
        {isLoading ? (
          <div className="h-4 w-48 animate-pulse rounded bg-muted" />
        ) : !stats || stats.topEndpoints.length === 0 ? (
          <p className="text-sm text-muted-foreground">No endpoint error data yet.</p>
        ) : (
          <ul className="flex flex-wrap gap-2">
            {stats.topEndpoints.map((e) => (
              <li key={e.endpoint}>
                <Badge variant="outline" className="whitespace-nowrap">
                  {e.endpoint} · {e.count}
                </Badge>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* Table */}
      <div className="flex flex-col gap-4">
        <p className="whitespace-nowrap text-sm text-muted-foreground">
          {isLoading ? 'Loading…' : `${entries.length} recent errors`}
        </p>

        <div className="overflow-x-auto rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Severity</TableHead>
                <TableHead>Message</TableHead>
                <TableHead>Endpoint</TableHead>
                <TableHead>User</TableHead>
                <TableHead>Occurred</TableHead>
                <TableHead>Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 6 }).map((__, j) => (
                      <TableCell key={j}>
                        <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : entries.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                    <div className="flex flex-col items-center gap-2">
                      <AlertOctagon className="size-6 text-muted-foreground/60" aria-hidden="true" />
                      No recent errors.
                    </div>
                  </TableCell>
                </TableRow>
              ) : (
                entries.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell>
                      <StatusPill tone={severityTone(entry.severity)}>{entry.severity}</StatusPill>
                    </TableCell>
                    <TableCell className="max-w-96 whitespace-normal text-foreground">{entry.message}</TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {entry.endpoint ?? '—'}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{entry.userId ?? '—'}</TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatDateTime(entry.createdAt)}
                    </TableCell>
                    <TableCell>
                      <ResolveButton entry={entry} onResolve={resolve} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}
