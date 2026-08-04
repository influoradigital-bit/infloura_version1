/**
 * INFLUORA ADMIN PANEL — Revenue Range Panel
 * Owner: Ananya (Frontend)
 *
 * Interactive (user-input -> fetch-on-submit) counterpart to the trailing-12-
 * bucket revenue trend already on the Finance console. Wires the previously
 * orphaned `financeApi.getRevenue(startDate, endDate, groupBy)`
 * (GET /admin/finance/revenue, AdminRevenueController) — a custom date-range
 * query distinct from `dashboardApi.getFinancialSummary`. Self-contained:
 * own local state, own fetch-on-submit, own loading/error/empty states.
 * Not part of `useFinanceConsole`'s mount-time `Promise.all` because it only
 * makes sense to call once the admin has picked a range.
 */

import { type FormEvent, useState } from 'react';
import { AlertTriangle, BadgeIndianRupee } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { financeApi } from '../../services/api-contracts';
import type { RevenueSnapshot } from '../../types/admin.types';

type RangeGroupBy = 'day' | 'week' | 'month';

const GROUP_BY_OPTIONS: { value: RangeGroupBy; label: string }[] = [
  { value: 'day', label: 'Daily' },
  { value: 'week', label: 'Weekly' },
  { value: 'month', label: 'Monthly' },
];

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

/** Last 30 days, inclusive of today — a sensible default range. */
function defaultRange(): { start: string; end: string } {
  const end = new Date();
  const start = new Date();
  start.setDate(start.getDate() - 30);
  return { start: toDateInputValue(start), end: toDateInputValue(end) };
}

function ErrorNotice({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 rounded-lg border border-destructive-foreground/30 bg-destructive-foreground/10 p-3 text-sm text-foreground">
      <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive-foreground" aria-hidden="true" />
      <p>{message}</p>
    </div>
  );
}

export default function RevenueRangePanel() {
  const initial = defaultRange();
  const [startDate, setStartDate] = useState(initial.start);
  const [endDate, setEndDate] = useState(initial.end);
  const [groupBy, setGroupBy] = useState<RangeGroupBy>('day');
  const [rows, setRows] = useState<RevenueSnapshot[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasQueried, setHasQueried] = useState(false);

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!startDate || !endDate) {
      setError('Pick both a start and end date.');
      return;
    }

    setIsLoading(true);
    setError(null);

    financeApi
      .getRevenue(startDate, endDate, groupBy)
      .then((res) => {
        if (res.success && res.data) {
          setRows(res.data);
          setError(null);
        } else {
          setRows([]);
          setError(res.error ?? 'Failed to load revenue for this range');
        }
      })
      .catch(() => {
        setRows([]);
        setError('Failed to load revenue for this range');
      })
      .finally(() => {
        setIsLoading(false);
        setHasQueried(true);
      });
  }

  return (
    <Card className="gap-4 p-5">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <BadgeIndianRupee className="size-4" aria-hidden="true" />
        Revenue Range Query
      </h3>
      <p className="text-xs text-muted-foreground">
        On-demand revenue breakdown for a custom date range — distinct from the trailing revenue
        trend above.
      </p>

      <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <div className="flex flex-col gap-1">
          <label htmlFor="revenue-range-start" className="text-xs font-medium text-muted-foreground">
            Start date
          </label>
          <Input
            id="revenue-range-start"
            type="date"
            value={startDate}
            onChange={(e) => setStartDate(e.target.value)}
            className="sm:w-40"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="revenue-range-end" className="text-xs font-medium text-muted-foreground">
            End date
          </label>
          <Input
            id="revenue-range-end"
            type="date"
            value={endDate}
            onChange={(e) => setEndDate(e.target.value)}
            className="sm:w-40"
            required
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="revenue-range-groupby" className="text-xs font-medium text-muted-foreground">
            Group by
          </label>
          <Select value={groupBy} onValueChange={(v) => setGroupBy(v as RangeGroupBy)}>
            <SelectTrigger id="revenue-range-groupby" className="w-32" aria-label="Group by">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {GROUP_BY_OPTIONS.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <Button type="submit" size="sm" disabled={isLoading}>
          {isLoading ? 'Loading…' : 'Load'}
        </Button>
      </form>

      {error && <ErrorNotice message={error} />}

      {hasQueried && !error && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Period</TableHead>
                <TableHead>GMV</TableHead>
                <TableHead>Platform Fees</TableHead>
                <TableHead>Setup Fees</TableHead>
                <TableHead>Total Revenue</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 4 }).map((_, i) => (
                  <TableRow key={i}>
                    {Array.from({ length: 5 }).map((__, j) => (
                      <TableCell key={j}>
                        <div className="h-4 w-full max-w-24 animate-pulse rounded bg-muted" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : !rows || rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                    No revenue data for this range.
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((snap) => (
                  <TableRow key={snap.period}>
                    <TableCell className="font-medium text-foreground">{snap.period}</TableCell>
                    <TableCell className="whitespace-nowrap text-foreground">
                      {formatCurrency(snap.gmv)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatCurrency(snap.platformFees)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {formatCurrency(snap.setupFees)}
                    </TableCell>
                    <TableCell className="whitespace-nowrap font-medium text-foreground">
                      {formatCurrency(snap.totalRevenue)}
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
