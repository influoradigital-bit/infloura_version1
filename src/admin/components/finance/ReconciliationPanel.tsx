/**
 * INFLUORA ADMIN PANEL — Reconciliation Panel
 * Owner: Ananya (Frontend)
 *
 * Interactive (user-input -> fetch-on-submit) counterpart wiring the
 * previously `unavailable()` `financeApi.getReconciliation(date)`
 * (GET /admin/finance/reconciliation?date=YYYY-MM-DD) and
 * `financeApi.retryPayout(id)` (POST /admin/finance/payouts/{id}/retry),
 * both flipped to real `apiRequest` calls 2026-08-04. Self-contained: own
 * local state, own fetch-on-submit, own loading/error/empty states — same
 * shape as RevenueRangePanel.tsx / EntityAuditPanel.tsx.
 */

import { type FormEvent, useState } from 'react';
import { AlertTriangle, ListChecks, RefreshCw } from 'lucide-react';
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
import { financeApi } from '../../services/api-contracts';
import type { ReconciliationItem } from '../../types/admin.types';

function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(amount);
}

function toDateInputValue(d: Date): string {
  return d.toISOString().slice(0, 10);
}

type PillTone = 'success' | 'warning' | 'destructive' | 'neutral';

const PILL_TONE_CLASSES: Record<PillTone, string> = {
  success: 'bg-success-foreground',
  warning: 'bg-warning-foreground',
  destructive: 'bg-destructive-foreground',
  neutral: 'bg-foreground',
};

function StatusPill({ tone, children }: { tone: PillTone; children: React.ReactNode }) {
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

function reconciliationStatusTone(status: ReconciliationItem['status']): PillTone {
  switch (status) {
    case 'MATCHED':
      return 'success';
    case 'MISMATCH':
      return 'destructive';
    case 'PENDING':
      return 'warning';
    default:
      return 'neutral';
  }
}

function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
      <p>{message}</p>
    </div>
  );
}

interface RetryState {
  status: 'idle' | 'in-flight' | 'success' | 'error';
  message?: string;
}

export default function ReconciliationPanel() {
  const [date, setDate] = useState(toDateInputValue(new Date()));
  const [rows, setRows] = useState<ReconciliationItem[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasQueried, setHasQueried] = useState(false);
  const [retryState, setRetryState] = useState<Record<string, RetryState>>({});

  function runReconciliation(forDate: string) {
    setIsLoading(true);
    setError(null);

    financeApi
      .getReconciliation(forDate)
      .then((res) => {
        if (res.success && res.data) {
          setRows(res.data);
          setError(null);
        } else {
          setRows([]);
          setError(res.error ?? 'Failed to load reconciliation for this date');
        }
      })
      .catch(() => {
        setRows([]);
        setError('Failed to load reconciliation for this date');
      })
      .finally(() => {
        setIsLoading(false);
        setHasQueried(true);
      });
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!date) {
      setError('Pick a date.');
      return;
    }
    runReconciliation(date);
  }

  function handleRetryPayout(row: ReconciliationItem) {
    if (!window.confirm(`Retry payout for internal id ${row.internalId}?`)) return;

    setRetryState((prev) => ({ ...prev, [row.id]: { status: 'in-flight' } }));

    financeApi
      .retryPayout(row.internalId)
      .then((res) => {
        if (res.success && res.data) {
          setRetryState((prev) => ({
            ...prev,
            [row.id]: { status: 'success', message: `Retried — now ${res.data!.status}` },
          }));
          // Refresh the reconciliation table so the retried row's status reflects reality.
          runReconciliation(date);
        } else {
          setRetryState((prev) => ({
            ...prev,
            [row.id]: { status: 'error', message: res.error ?? 'Retry failed' },
          }));
        }
      })
      .catch(() => {
        setRetryState((prev) => ({
          ...prev,
          [row.id]: { status: 'error', message: 'Retry failed' },
        }));
      });
  }

  return (
    <Card className="gap-4 p-5">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <ListChecks className="size-4" aria-hidden="true" />
        Reconciliation
      </h3>
      <p className="text-xs text-muted-foreground">
        Razorpay vs. internal ledger for a given day. Mismatched rows backed by a genuinely-failed
        payout can be retried directly.
      </p>

      <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <div className="flex flex-col gap-1">
          <label htmlFor="reconciliation-date" className="text-xs font-medium text-muted-foreground">
            Date
          </label>
          <Input
            id="reconciliation-date"
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            className="sm:w-40"
            required
          />
        </div>

        <Button type="submit" size="sm" disabled={isLoading}>
          {isLoading ? 'Reconciling…' : 'Reconcile'}
        </Button>
      </form>

      {error && <ErrorNotice message={error} />}

      {hasQueried && !error && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Internal ID</TableHead>
                <TableHead>Razorpay ID</TableHead>
                <TableHead>Internal Amount</TableHead>
                <TableHead>Razorpay Amount</TableHead>
                <TableHead>Variance</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Retry</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 4 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 7 }).map((__, j) => (
                      <TableCell key={j}>
                        <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : !rows || rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="py-10 text-center text-sm text-muted-foreground">
                    No reconciliation records for this date.
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((row) => {
                  const retry = retryState[row.id];
                  return (
                    <TableRow key={row.id}>
                      <TableCell className="whitespace-nowrap font-medium text-foreground">
                        {row.internalId}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-muted-foreground">
                        {row.razorpayId}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-foreground">
                        {formatCurrency(row.internalAmount)}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-foreground">
                        {formatCurrency(row.razorpayAmount)}
                      </TableCell>
                      <TableCell
                        className={cn(
                          'whitespace-nowrap',
                          row.variance !== 0 ? 'text-destructive-foreground' : 'text-muted-foreground',
                        )}
                      >
                        {formatCurrency(row.variance)}
                      </TableCell>
                      <TableCell>
                        <StatusPill tone={reconciliationStatusTone(row.status)}>{row.status}</StatusPill>
                      </TableCell>
                      <TableCell className="min-w-40">
                        {row.status === 'MISMATCH' ? (
                          <div className="flex flex-col gap-1">
                            <Button
                              type="button"
                              size="sm"
                              variant="destructive"
                              disabled={retry?.status === 'in-flight'}
                              onClick={() => handleRetryPayout(row)}
                            >
                              <RefreshCw className="size-3.5" aria-hidden="true" />
                              {retry?.status === 'in-flight' ? 'Retrying…' : 'Retry payout'}
                            </Button>
                            {retry?.status === 'success' && (
                              <span className="text-xs text-success-foreground">{retry.message}</span>
                            )}
                            {retry?.status === 'error' && (
                              <span className="text-xs text-destructive-foreground">{retry.message}</span>
                            )}
                          </div>
                        ) : (
                          <span className="text-xs text-muted-foreground">—</span>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })
              )}
            </TableBody>
          </Table>
        </div>
      )}
    </Card>
  );
}
