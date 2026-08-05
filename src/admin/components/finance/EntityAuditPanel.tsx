/**
 * INFLUORA ADMIN PANEL — Entity Audit Panel
 * Owner: Ananya (Frontend)
 *
 * Interactive (user-input -> fetch-on-submit) lookup wiring the previously
 * orphaned `auditApi.getByEntity(entityType, entityId)`
 * (GET /admin/audit/entity/{entityType}/{entityId}, AuditLogController) — a
 * targeted "show me every admin action against this one record" view,
 * distinct from the paginated `auditApi.list()` feed on AuditLogPage.tsx.
 * Self-contained: own local state, own fetch-on-submit, own
 * loading/error/empty states. Row rendering mirrors AuditLogPage.tsx.
 */

import { type FormEvent, useState } from 'react';
import { AlertTriangle, SearchCheck } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';
import { auditApi } from '../../services/api-contracts';
import type { AuditLogEntry, AuditLogSource } from '../../types/admin.types';

function formatDateTime(iso: string | undefined): string {
  if (!iso) return '—';
  try {
    return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  } catch {
    return 'Invalid date';
  }
}

function formatChange(entry: AuditLogEntry): string {
  if (!entry.oldValue && !entry.newValue) return '—';
  return `${entry.oldValue ?? '—'} → ${entry.newValue ?? '—'}`;
}

// Same solid-fill source pill as AuditLogPage.tsx (Kabir finding 2.1 — a
// client-submitted row must be visibly distinct from an authoritative one).
const SOURCE_PILL_CLASSES: Record<AuditLogSource, string> = {
  SERVER_INTERNAL: 'bg-foreground',
  CLIENT_REPORTED: 'bg-warning-foreground',
};

const SOURCE_LABEL: Record<AuditLogSource, string> = {
  SERVER_INTERNAL: 'System',
  CLIENT_REPORTED: 'Reported',
};

function SourcePill({ source }: { source: AuditLogSource }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold text-white',
        SOURCE_PILL_CLASSES[source],
      )}
    >
      {SOURCE_LABEL[source]}
    </span>
  );
}

function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
      <p>{message}</p>
    </div>
  );
}

export default function EntityAuditPanel() {
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [entries, setEntries] = useState<AuditLogEntry[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasQueried, setHasQueried] = useState(false);

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const type = entityType.trim();
    const id = entityId.trim();
    if (!type || !id) {
      setError('Enter both an entity type and an entity id.');
      return;
    }

    setIsLoading(true);
    setError(null);

    auditApi
      .getByEntity(type, id)
      .then((res) => {
        if (res.success && res.data) {
          setEntries(res.data);
          setError(null);
        } else {
          setEntries([]);
          setError(res.error ?? 'Failed to load audit entries for this entity');
        }
      })
      .catch(() => {
        setEntries([]);
        setError('Failed to load audit entries for this entity');
      })
      .finally(() => {
        setIsLoading(false);
        setHasQueried(true);
      });
  }

  return (
    <Card className="gap-4 p-5">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <SearchCheck className="size-4" aria-hidden="true" />
        Entity Audit Lookup
      </h3>
      <p className="text-xs text-muted-foreground">
        Every admin action recorded against one specific record, by entity type and id.
      </p>

      <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <div className="flex flex-col gap-1">
          <label htmlFor="entity-audit-type" className="text-xs font-medium text-muted-foreground">
            Entity type
          </label>
          <Input
            id="entity-audit-type"
            value={entityType}
            onChange={(e) => setEntityType(e.target.value)}
            placeholder="e.g. BRAND, CAMPAIGN…"
            className="sm:w-40"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="entity-audit-id" className="text-xs font-medium text-muted-foreground">
            Entity id
          </label>
          <Input
            id="entity-audit-id"
            value={entityId}
            onChange={(e) => setEntityId(e.target.value)}
            placeholder="e.g. 42"
            className="sm:w-40"
            required
          />
        </div>

        <Button type="submit" size="sm" disabled={isLoading}>
          {isLoading ? 'Looking up…' : 'Look up'}
        </Button>
      </form>

      {error && <ErrorNotice message={error} />}

      {hasQueried && !error && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Timestamp</TableHead>
                <TableHead>Admin</TableHead>
                <TableHead>Action</TableHead>
                <TableHead>Change</TableHead>
                <TableHead>Reason</TableHead>
                <TableHead>Source</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 3 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 6 }).map((__, j) => (
                      <TableCell key={j}>
                        <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : !entries || entries.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                    No audit entries for this entity.
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
                    <TableCell className="max-w-64 whitespace-normal text-muted-foreground">
                      {formatChange(entry)}
                    </TableCell>
                    <TableCell className="max-w-56 whitespace-normal text-muted-foreground">
                      {entry.reason ?? '—'}
                    </TableCell>
                    <TableCell>
                      <SourcePill source={entry.source} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}
